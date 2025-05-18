import com.boomi.execution.ExecutionUtil
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.io.InputStream
import java.io.ByteArrayInputStream
import java.util.Properties
import java.util.regex.Pattern
import java.util.regex.Matcher

for (int i = 0; i < dataContext.getDataCount(); i++) {

    InputStream inputStream = dataContext.getStream(i)
    Properties properties = dataContext.getProperties(i)

    ObjectMapper objectMapper = new ObjectMapper()
    String jsonString = inputStream.text
    JsonNode rootNode = objectMapper.readTree(jsonString)

    JsonNode itemNode = rootNode.path("collection").path("item").get(0)
    JsonNode requestNode = itemNode.path("request")
    String rawUrl = requestNode.path("url").path("raw").asText()

    JsonNode hostArray = requestNode.path("url").path("host")
    List<String> hostParts = []

    for (int j = 0; j < hostArray.size(); j++) {
        hostParts.add(hostArray.get(j).asText())
    }

    String hostString = hostParts.join(".")

    String baseUrl = ""
    if (rawUrl.contains(hostString)) {
        int endIndex = rawUrl.indexOf(hostString) + hostString.length()
        baseUrl = rawUrl.substring(0, endIndex)
    } else {
        String lastHost = hostParts[-1]
        int lastIndex = rawUrl.lastIndexOf(lastHost)
        if (lastIndex != -1) {
            int endIndex = lastIndex + lastHost.length()
            baseUrl = rawUrl.substring(0, endIndex)
        }
    }

    JsonNode variablesNode = rootNode.path("collection").path("variable")
    Map<String, String> collectionVariables = [:]

    for (int k = 0; k < variablesNode.size(); k++) {
        JsonNode variableNode = variablesNode.get(k)
        String key = variableNode.path("key").asText()
        String value = variableNode.path("value").asText()
        collectionVariables.put(key, value)
    }

    String updatedBaseUrl = baseUrl
    List<String> unresolvedVariables = []

    Pattern placeholderPattern = Pattern.compile("\\{\\{(.*?)\\}\\}")
    Matcher matcher = placeholderPattern.matcher(baseUrl)

    while (matcher.find()) {
        String placeholder = matcher.group(0)
        String key = matcher.group(1)

        if (collectionVariables.containsKey(key)) {
            String value = collectionVariables.get(key)
            updatedBaseUrl = updatedBaseUrl.replace(placeholder, value)
        } else {
            unresolvedVariables.add(placeholder)
        }
    }

    if (!unresolvedVariables.isEmpty()) {
        updatedBaseUrl = ""
    }

    properties.setProperty("document.dynamic.userdefined.DDP_BaseURL", updatedBaseUrl)

    InputStream restoredStream = new ByteArrayInputStream(jsonString.bytes)
    dataContext.storeStream(restoredStream, properties)
}
