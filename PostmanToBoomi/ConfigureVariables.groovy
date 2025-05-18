import com.boomi.execution.ExecutionUtil
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.databind.node.ArrayNode

import java.io.InputStream
import java.io.ByteArrayInputStream
import java.util.regex.Pattern
import java.util.regex.Matcher
import java.util.Properties

ObjectMapper mapper = new ObjectMapper()
Pattern varPattern = Pattern.compile("\\{\\{(.*?)\\}\\}")

for (int i = 0; i < dataContext.getDataCount(); i++) {
    InputStream inputStream = dataContext.getStream(i)
    String jsonString = inputStream.getText()

    JsonNode root = mapper.readTree(jsonString)
    JsonNode collectionNode = root.get("collection")

    Map varMap = [:]
    if (collectionNode.has("variable")) {
        def vars = collectionNode.get("variable")
        for (int v = 0; v < vars.size(); v++) {
            JsonNode varNode = vars.get(v)
            String key = varNode.get("key").asText()
            String value = varNode.has("value") && !varNode.get("value").isNull() ? varNode.get("value").asText() : ""
            varMap.put(key, value)
        }
    }

    def replaceVars = { String input ->
        if (input == null) return ""
        Matcher matcher = varPattern.matcher(input)
        StringBuffer sb = new StringBuffer()
        while (matcher.find()) {
            String varName = matcher.group(1)
            String replacement = varMap.containsKey(varName) ? varMap[varName] : ""
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement))
        }
        matcher.appendTail(sb)
        return sb.toString()
    }

    def processAuthArray = { ArrayNode authArray ->
        for (int j = 0; j < authArray.size(); j++) {
            JsonNode entry = authArray.get(j)
            if (entry.has("value")) {
                JsonNode valNode = entry.get("value")
                if (valNode.isTextual()) {
                    String replaced = replaceVars(valNode.asText())
                    ((ObjectNode) entry).put("value", replaced)
                } else if (valNode.isArray()) {
                    ArrayNode nestedArray = (ArrayNode) valNode
                    for (int k = 0; k < nestedArray.size(); k++) {
                        JsonNode nestedEntry = nestedArray.get(k)
                        if (nestedEntry.has("value") && nestedEntry.get("value").isTextual()) {
                            String nestedReplaced = replaceVars(nestedEntry.get("value").asText())
                            ((ObjectNode) nestedEntry).put("value", nestedReplaced)
                        }
                    }
                }
            }
        }
    }

    if (collectionNode.has("auth")) {
        ObjectNode authNode = (ObjectNode) collectionNode.get("auth")
        ['basic', 'oauth1', 'oauth2', 'awsv4'].each { authType ->
            if (authNode.has(authType)) {
                processAuthArray((ArrayNode) authNode.get(authType))
            }
        }
    }

    if (collectionNode.has("item") && collectionNode.get("item").isArray()) {
        ArrayNode items = (ArrayNode) collectionNode.get("item")
        for (int x = 0; x < items.size(); x++) {
            JsonNode item = items.get(x)
            if (item.has("request") && item.get("request").has("auth")) {
                ObjectNode requestAuthNode = (ObjectNode) item.get("request").get("auth")
                ['basic', 'oauth1', 'oauth2', 'awsv4'].each { authType ->
                    if (requestAuthNode.has(authType)) {
                        processAuthArray((ArrayNode) requestAuthNode.get(authType))
                    }
                }
            }
        }
    }

    String updatedJson = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root)
    InputStream outputStream = new ByteArrayInputStream(updatedJson.getBytes("UTF-8"))
    Properties props = new Properties()
    dataContext.storeStream(outputStream, props)
}
