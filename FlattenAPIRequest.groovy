import com.boomi.execution.ExecutionUtil
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.databind.node.ArrayNode

import java.io.InputStream
import java.io.ByteArrayInputStream
import java.util.Properties
import java.util.ArrayList

ObjectMapper mapper = new ObjectMapper()

for (int i = 0; i < dataContext.getDataCount(); i++) {
    InputStream inputStream = dataContext.getStream(i)
    String jsonString = inputStream.getText()
    JsonNode root = mapper.readTree(jsonString)

    JsonNode collectionNode = root.get("collection")
    JsonNode originalItems = collectionNode.get("item")

    ArrayNode flatItems = mapper.createArrayNode()
    ArrayList nodesToVisit = new ArrayList()
    Iterator itemIterator = originalItems.elements()
    while (itemIterator.hasNext()) {
        nodesToVisit.add(itemIterator.next())
    }

    while (!nodesToVisit.isEmpty()) {
        JsonNode current = nodesToVisit.remove(0)
        if (current.has("request")) {
            flatItems.add(current)
        }
        if (current.has("item")) {
            Iterator subItems = current.get("item").elements()
            while (subItems.hasNext()) {
                nodesToVisit.add(subItems.next())
            }
        }
    }

    for (int j = 0; j < flatItems.size(); j++) {
        JsonNode itemNode = flatItems.get(j)

        ObjectNode newRoot = mapper.createObjectNode()
        ObjectNode newCollection = mapper.createObjectNode()

        if (collectionNode.has("info")) {
            newCollection.set("info", collectionNode.get("info"))
        }

        if (collectionNode.has("auth")) {
            newCollection.set("auth", collectionNode.get("auth"))
        }

        if (collectionNode.has("variable")) {
            newCollection.set("variable", collectionNode.get("variable"))
        }

        if (collectionNode.has("event")) {
            newCollection.set("event", collectionNode.get("event"))
        }

        ArrayNode singleItemArray = mapper.createArrayNode()
        singleItemArray.add(itemNode)
        newCollection.set("item", singleItemArray)

        newRoot.set("collection", newCollection)

        String outputJson = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(newRoot)
        InputStream outputStream = new ByteArrayInputStream(outputJson.getBytes("UTF-8"))

        Properties props = new Properties()
        dataContext.storeStream(outputStream, props)
    }
}
