import com.boomi.execution.ExecutionUtil
import com.fasterxml.jackson.databind.ObjectMapper
import java.io.ByteArrayInputStream

def encodeForXml(String value) {
    if (value == null) return ""
    return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;")
                .replaceAll("[^\\x20-\\x7E]", "")
}

def getAuthType(String type) {
    if (type == null) return "NONE"
    switch (type.toLowerCase()) {
        case "basic": return "BASIC"
        case "oauth1": return "OAUTH"
        case "oauth2": return "OAUTH2"
        case "awsv4": return "AWSV4"
        default: return "NONE"
    }
}

def getSignatureMethod(String sigMethod) {
    if (sigMethod == null) return "SHA256"
    switch (sigMethod.toUpperCase()) {
        case "HMAC-SHA1": return "SHA1"
        case "HMAC-SHA256": return "SHA256"
        default: return "SHA256"
    }
}

def allowedAwsRegions = [
    "ap-northeast-1", "ap-northeast-2", "ap-south-1", "ap-southeast-1",
    "ap-southeast-2", "ca-central-1", "cn-north-1", "eu-central-1",
    "eu-west-1", "eu-west-2", "sa-east-1", "us-east-1", "us-east-2",
    "us-west-1", "us-west-2"
]

for (int i = 0; i < dataContext.getDataCount(); i++) {
    def inputStream = dataContext.getStream(i)
    def props = dataContext.getProperties(i)
    def json = new ObjectMapper().readTree(inputStream.text)

    def collection = json.path("collection")
    def info = collection.path("info")
    def connName = encodeForXml(info.path("name").asText())

    def folderId = ExecutionUtil.getDynamicProcessProperty("DPP_folderId")
    def branchId = ExecutionUtil.getDynamicProcessProperty("DPP_branchId")
    def baseUrl = props.getProperty("document.dynamic.userdefined.DDP_BaseURL")

    def auth = collection.path("auth")
    def authTypeRaw = auth.path("type").asText()

    def items = collection.path("item")
    if (items.isArray() && items.size() > 0) {
        def firstItemAuth = items.get(0).path("request").path("auth")
        if (!firstItemAuth.isMissingNode() && !firstItemAuth.isNull()) {
            auth = firstItemAuth
            authTypeRaw = auth.path("type").asText()
        }
    }

    def authType = getAuthType(authTypeRaw)

    def user = "", password = "", clientID = "", clientSecret = "", scope = ""
    def refreshTokenUrl = "", authzTokenUrl = "", grantType = "client_credentials"
    def username = "", passwordCred = "", signatureMethod = "SHA256", accessToken = ""
    def accessTokenURL = "", authorizationURL = "", consumerKey = "", consumerSecret = ""
    def tokenSecret = "", requestTokenURL = "", realm = ""

    def awsAccessKey = "", awsSecretKey = "", awsRegion = "Custom", awsService = "Custom"
    def customService = "", customRegion = ""

    def authRequestParamsXml = ""
    def accessTokenParamsXml = ""

    if ("BASIC".equals(authType)) {
        auth.path("basic").each { item ->
            switch (item.path("key").asText()) {
                case "username": user = item.path("value").asText(); break
                case "password": password = item.path("value").asText(); break
            }
        }
    }

    if ("OAUTH2".equals(authType)) {
        auth.path("oauth2").each { item ->
            def key = item.path("key").asText()
            def value = item.path("value").asText()

            switch (key) {
                case "clientId": clientID = value; break
                case "clientSecret": clientSecret = value; break
                case "scope": scope = value; break
                case "refreshTokenUrl": refreshTokenUrl = value; break
                case "authUrl": authzTokenUrl = value; break
                case "username": username = value; break
                case "password": passwordCred = value; break
                case "signatureMethod": signatureMethod = getSignatureMethod(value); break

                case "authRequestParams":
                    def authReqParamsNode = item.path("value")
                    if (authReqParamsNode.isArray()) {
                        authReqParamsNode.each { param ->
                            if (param.path("enabled").asBoolean(true)) {
                                def pname = param.path("key").asText()
                                def pval = param.path("value").asText()
                                authRequestParamsXml += "<parameter name=\"${encodeForXml(pname)}\" value=\"${encodeForXml(pval)}\"/>"
                            }
                        }
                    }
                    break

                case "tokenRequestParams":
                    def tokenReqParamsNode = item.path("value")
                    if (tokenReqParamsNode.isArray()) {
                        tokenReqParamsNode.each { param ->
                            if (param.path("enabled").asBoolean(true)) {
                                def pname = param.path("key").asText()
                                def pval = param.path("value").asText()
                                accessTokenParamsXml += "<parameter name=\"${encodeForXml(pname)}\" value=\"${encodeForXml(pval)}\"/>"
                            }
                        }
                    }
                    break
            }
        }

        def grantTypeInbound = ""
        auth.path("oauth2").each { item ->
            if (item.path("key").asText() == "grant_type") {
                grantTypeInbound = item.path("value").asText()
            }
        }

        switch (grantTypeInbound) {
            case "authorization_code":
                grantType = "code"
                break
            case "password_credentials":
                grantType = "password"
                break
            case "client_credentials":
                grantType = "client_credentials"
                break
            default:
                grantType = "code"
                break
        }

        if ("password".equalsIgnoreCase(grantType)) {
            accessTokenParamsXml += "<parameter name=\"username\" value=\"${encodeForXml(username)}\"/>" +
                                    "<parameter name=\"password\" value=\"${encodeForXml(passwordCred)}\"/>"
        } //If the User doesnt need the Username and Password populating in the accessTokenParams remove this if block
    }

    if ("OAUTH".equals(authType)) {
        auth.path("oauth1").each { item ->
            switch (item.path("key").asText()) {
                case "consumerKey": consumerKey = item.path("value").asText(); break
                case "consumerSecret": consumerSecret = item.path("value").asText(); break
                case "token": accessToken = item.path("value").asText(); break
                case "tokenSecret": tokenSecret = item.path("value").asText(); break
                case "signatureMethod": signatureMethod = getSignatureMethod(item.path("value").asText()); break
                case "realm": realm = item.path("value").asText(); break
                //case "accessTokenUrl": accessTokenURL = item.path("value").asText(); break
                //case "requestTokenUrl": requestTokenURL = item.path("value").asText(); break
                //case "authUrl": authorizationURL = item.path("value").asText(); break
            }
        }
    }

    if ("AWSV4".equals(authType)) {
        def svcInbound = ""
        auth.path("awsv4").each { item ->
            switch (item.path("key").asText()) {
                case "accessKey": awsAccessKey = item.path("value").asText(); break
                case "secretKey": awsSecretKey = item.path("value").asText(); break
                case "region": awsRegion = item.path("value").asText(); break
                case "service": svcInbound = item.path("value").asText(); break
            }
        }

        if (allowedAwsRegions.contains(svcInbound)) {
            awsService = svcInbound
            customService = ""
        } else {
            awsService = "Custom"
            customService = svcInbound ?: ""
        }

        if (!allowedAwsRegions.contains(awsRegion)) {
            customRegion = awsRegion
            awsRegion = "Custom"
        }
    }

    accessToken = props.getProperty("document.dynamic.userdefined.DDP_AccessToken") ?: accessToken

    def xml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<bns:Component xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
               xmlns:bns="http://api.platform.boomi.com/"
               name="${connName}"
               subType="http"
               type="connector-settings"
               deleted="false"
               currentVersion="true"
               branchName="main"
               folderId="${folderId}"
               branchId="${branchId}">
    <bns:encryptedValues/>
    <bns:description/>
    <bns:object>
        <HttpSettings authenticationType="${authType}" url="${encodeForXml(baseUrl)}">
            <AuthSettings password="${password}" user="${encodeForXml(user)}"/>
            <OAuthSettings accessToken="${accessToken}"
                           accessTokenURL="${encodeForXml(accessTokenURL)}"
                           authorizationURL="${encodeForXml(authorizationURL)}"
                           consumerKey="${encodeForXml(consumerKey)}"
                           realm="${encodeForXml(realm)}"
                           requestTokenURL="${encodeForXml(requestTokenURL)}"
                           signatureMethod="${encodeForXml(signatureMethod)}"
                           suppressBlankAccessToken="false"
                           tokenSecret="${encodeForXml(tokenSecret)}"
                           consumerSecret="${encodeForXml(consumerSecret)}"/>
            <OAuth2Settings grantType="${encodeForXml(grantType)}" refreshAuthScheme="req_body_params_auth">
                <credentials accessToken="${encodeForXml(accessToken)}"
                             clientId="${encodeForXml(clientID)}"
                             clientSecret="${encodeForXml(clientSecret)}"/>
                <authorizationTokenEndpoint url="${encodeForXml(authzTokenUrl)}">
                    <sslOptions/>
                </authorizationTokenEndpoint>
                <authorizationParameters>${authRequestParamsXml}</authorizationParameters>
                <accessTokenEndpoint url="${encodeForXml(refreshTokenUrl)}">
                    <sslOptions/>
                </accessTokenEndpoint>
                <accessTokenParameters>${accessTokenParamsXml}</accessTokenParameters>
                <scope>${encodeForXml(scope)}</scope>
            </OAuth2Settings>
            <AwsSettings>
                <credentials>
                    <accessKeyId>${encodeForXml(awsAccessKey)}</accessKeyId>
                    <awsSecret>${encodeForXml(awsSecretKey)}</awsSecret>
                    <awsService>${encodeForXml(awsService)}</awsService>
                    <customService>${encodeForXml(customService)}</customService>
                    <awsRegion>${encodeForXml(awsRegion)}</awsRegion>
                    <customRegion>${encodeForXml(customRegion)}</customRegion>
                </credentials>
            </AwsSettings>
            <SSLOptions clientauth="false" trustServerCert="false"/>
        </HttpSettings>
    </bns:object>
</bns:Component>"""

    def outputStream = new ByteArrayInputStream(xml.getBytes("UTF-8"))
    dataContext.storeStream(outputStream, props)
}
