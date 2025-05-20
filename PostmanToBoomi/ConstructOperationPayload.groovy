import com.boomi.execution.ExecutionUtil
import com.fasterxml.jackson.databind.ObjectMapper
import java.io.ByteArrayInputStream

def capitalize(String str) {
    return (str && str.length() > 0) ? str[0].toUpperCase() + str.substring(1).toLowerCase() : ""
}

def encodeForXml(String value) {
    if (value == null) return ""
    return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;")
                .replaceAll("[^\\x20-\\x7E]", "")
}

def parseUrlToXmlElements(String url, int startingKey) {
    def xml = new StringBuilder()
    def pattern = /(.*?)\{\{(.*?)\}\}/
    def matcher = url =~ pattern
    int key = startingKey
    int lastIndex = 0

    while (matcher.find()) {
        def staticPart = matcher.group(1)
        def varName = matcher.group(2)

        if (staticPart) {
            xml.append("""<element isVariable="false" key="${key}" name="${encodeForXml(staticPart)}"/>\n""")
            key++
        }

        if (varName) {
            xml.append("""<element isVariable="true" key="${key}" name="${encodeForXml(varName)}"/>\n""")
            key++
        }

        lastIndex = matcher.end()
    }

    if (lastIndex < url.length()) {
        def remaining = url.substring(lastIndex)
        if (remaining) {
            xml.append("""<element isVariable="false" key="${key}" name="${encodeForXml(remaining)}"/>\n""")
            key++
        }
    }

    return [xml: xml.toString(), nextKey: key]
}

for (int i = 0; i < dataContext.getDataCount(); i++) {
    def inputStream = dataContext.getStream(i)
    def props = dataContext.getProperties(i)
    def mapper = new ObjectMapper()
    def jsonString = inputStream.text
    def root = mapper.readTree(jsonString)
    def folderId = ExecutionUtil.getDynamicProcessProperty("DPP_folderId")
    def branchId = ExecutionUtil.getDynamicProcessProperty("DPP_branchId")

    def items = root.path("item")
    if (items.isMissingNode()) {
        items = root.path("collection").path("item")
    }

    def collectionVariables = [:]
    def variablesNode = root.path("collection").path("variable")
    if (variablesNode.isArray()) {
        variablesNode.each { varNode ->
            def key = varNode.path("key").asText()
            def value = varNode.path("value").asText()
            if (key) {
                collectionVariables[key] = value
            }
        }
    }

    items.elements().each { item ->
        def request = item.path("request")
        def rawMethod = request.path("method").asText(null)
        if (!rawMethod || !["GET", "PUT", "POST", "DELETE"].contains(rawMethod.toUpperCase())) return

        def method = rawMethod.toUpperCase()
        def methodType = capitalize(rawMethod.toLowerCase())
        def operationName = item.path("name").asText().replaceAll("\\s+", "_")

        def headerXml = new StringBuilder()
        def contentType = "text/plain"
        def headers = request.path("header")
        def headerElementKey = 1000000

        if (headers.isArray()) {
            headers.each { h ->
                def key = h.path("key").asText()
                def value = h.path("value").asText()
                if (key.toLowerCase() == "content-type") {
                    contentType = value
                }

                def isVariable = "false"
                def headerValue = value

                if (!value?.trim()) {
                    isVariable = "true"
                    headerValue = key
                } else if (value ==~ /\{\{.*\}\}/) {
                    def varName = value.replaceAll(/\{\{|\}\}/, "")
                    if (collectionVariables.containsKey(varName)) {
                        headerValue = collectionVariables[varName]
                        isVariable = "true" //The user can set this value to "false" to use the header values defined in the Collection Variables instead.
                    } else {
                        headerValue = varName
                        isVariable = "true"
                    }
                }

                headerXml.append("""<header headerName="${encodeForXml(key)}" key="${headerElementKey}" headerValue="${encodeForXml(headerValue)}" isVariable="${isVariable}"/>\n""")
                headerElementKey++
            }
        }

        def profileType = contentType.contains("application/json") ? "JSON" :
                          contentType.contains("application/xml") ? "XML" : "NONE"

        def rawUrl = request.path("url").path("raw").asText()
        def hostList = []
        def hostNode = request.path("url").path("host")
        if (hostNode.isArray()) {
            hostNode.each { hn -> hostList << hn.asText() }
        }

        String updatedUrl = rawUrl

        def baseUrl = ""
        def hostString = hostList.join(".")
        if (updatedUrl.contains(hostString)) {
            int endIndex = updatedUrl.indexOf(hostString) + hostString.length()
            baseUrl = updatedUrl.substring(0, endIndex)
        } else if (!hostList.isEmpty()) {
            def lastHost = hostList[-1]
            def lastIndex = updatedUrl.lastIndexOf(lastHost)
            if (lastIndex != -1) {
                def endIndex = lastIndex + lastHost.length()
                baseUrl = updatedUrl.substring(0, endIndex)
            }
        }

        def subPath = updatedUrl.substring(baseUrl.length())
        def queryString = ""
        if (subPath.contains("?")) {
            queryString = subPath.substring(subPath.indexOf("?"))
            subPath = subPath.substring(0, subPath.indexOf("?"))
        }

        def pathElementKey = 2000000
        def pathResult = parseUrlToXmlElements(subPath, pathElementKey)
        def pathXml = pathResult.xml
        pathElementKey = pathResult.nextKey

        def queryXml = ""
        if (queryString) {
            def queryResult = parseUrlToXmlElements(queryString, pathElementKey)
            queryXml = queryResult.xml
            pathElementKey = queryResult.nextKey
        }

        def actionType = (method == "GET") ? "HttpGetAction" : "HttpSendAction"

        def headersSection = """<requestHeaders>
            ${headerXml.toString().trim()}
        </requestHeaders>"""

        def pathSection = """<pathElements>
            ${pathXml.trim() + "\n" + queryXml.trim()}
        </pathElements>"""

        def xml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<bns:Component xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
               xmlns:bns="http://api.platform.boomi.com/"
               name="${encodeForXml(operationName)}"
               type="connector-action"
               subType="http"
               currentVersion="true"
               deleted="false"
               branchName="main"
               branchId="${branchId}"
               folderId="${folderId}">
    <bns:encryptedValues/>
    <bns:description></bns:description>
    <bns:object>
        <Operation xmlns="">
            <Archiving directory="" enabled="false"/>
            <Configuration>
                <${actionType} dataContentType="${contentType}"
                               followRedirects="false"
                               methodType="${method}"
                               mimePassthrough="false"
                               requestProfileType="${profileType}"
                               responseProfileType="${profileType}"
                               returnErrors="true"
                               returnMimeResponse="false">
                    ${headersSection}
                    ${pathSection}
                    <responseHeaderMapping/>
                    <reflectHeaders/>
                </${actionType}>
            </Configuration>
            <Tracking>
                <TrackedFields/>
            </Tracking>
            <Caching/>
        </Operation>
    </bns:object>
</bns:Component>"""

        def outputStream = new ByteArrayInputStream(xml.getBytes("UTF-8"))
        def outProps = new Properties()
        dataContext.storeStream(outputStream, outProps)
    }
}
