package operations;

import com.google.gson.*;
import crdt.CrdtId;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

// Gson <-> Operation records — type field yfar2 ben kol op
public class OperationSerializer {

    private static final Gson GSON = new GsonBuilder()
            .registerTypeHierarchyAdapter(Operation.class, new OperationAdapter())
            .create();

    // toJson — op -> JSON string
    public static String toJson(Operation op) {
        return GSON.toJson(op, Operation.class);
    }

    // fromJson — JSON string -> op
    public static Operation fromJson(String json) {
        return GSON.fromJson(json, Operation.class);
    }

    // listToJson — array JSON men list ops
    public static String listToJson(List<Operation> ops) {
        JsonArray arr = new JsonArray();
        for (Operation op : ops) {

            arr.add(JsonParser.parseString(toJson(op)));
        }
        return arr.toString();
    }

    // listFromJson — parse array -> list ops
    public static List<Operation> listFromJson(String json) {
        JsonArray arr = JsonParser.parseString(json).getAsJsonArray();
        List<Operation> ops = new ArrayList<>();
        for (JsonElement el : arr) {
            ops.add(fromJson(el.toString()));
        }
        return ops;
    }

    private static final class OperationAdapter
            implements JsonSerializer<Operation>, JsonDeserializer<Operation> {

        // serialize — switch 3la record type -> JsonObject
        @Override
        public JsonElement serialize(Operation op, Type typeOfSrc, JsonSerializationContext ctx) {
            JsonObject obj = new JsonObject();

            switch (op) {                case CharInsertOp o -> {
                    obj.addProperty("type", "CharInsert");
                    obj.add("blockId",  idJson(o.blockId()));
                    obj.add("charId",   idJson(o.charId()));

                    obj.add("parentId", o.parentId() == null ? JsonNull.INSTANCE : idJson(o.parentId()));

                    obj.addProperty("value",  String.valueOf(o.value()));
                    obj.addProperty("bold",   o.bold());
                    obj.addProperty("italic", o.italic());
                }
                case CharDeleteOp o -> {
                    obj.addProperty("type", "CharDelete");
                    obj.add("blockId", idJson(o.blockId()));
                    obj.add("charId",  idJson(o.charId()));
                    obj.addProperty("opClock", o.opClock());
                }
                case CharUndeleteOp o -> {
                    obj.addProperty("type", "CharUndelete");
                    obj.add("blockId", idJson(o.blockId()));
                    obj.add("charId",  idJson(o.charId()));
                    obj.addProperty("opClock", o.opClock());
                }
                case FormatOp o -> {
                    obj.addProperty("type", "Format");
                    obj.add("blockId", idJson(o.blockId()));
                    obj.add("charId",  idJson(o.charId()));
                    obj.addProperty("bold",    o.bold());
                    obj.addProperty("italic",  o.italic());
                    obj.addProperty("opClock", o.opClock());
                }
                case BlockInsertOp o -> {
                    obj.addProperty("type", "BlockInsert");
                    obj.add("blockId",  idJson(o.blockId()));
                    obj.add("parentId", o.parentId() == null ? JsonNull.INSTANCE : idJson(o.parentId()));
                }
                case BlockDeleteOp o -> {
                    obj.addProperty("type", "BlockDelete");
                    obj.add("blockId", idJson(o.blockId()));
                    obj.addProperty("opClock", o.opClock());
                }
                case BlockSplitOp o -> {
                    obj.addProperty("type", "BlockSplit");
                    obj.add("originalBlockId",  idJson(o.originalBlockId()));
                    obj.add("newBlockId",        idJson(o.newBlockId()));
                    obj.addProperty("splitAfterIndex", o.splitAfterIndex());
                    obj.addProperty("opClock",         o.opClock());
                }
                case FormatRestoreOp o -> {
                    obj.addProperty("type", "FormatRestore");
                    obj.add("blockId",  idJson(o.blockId()));
                    obj.add("charId",   idJson(o.charId()));
                    obj.addProperty("targetBold",   o.targetBold());
                    obj.addProperty("targetItalic", o.targetItalic());
                    obj.addProperty("otherBold",    o.otherBold());
                    obj.addProperty("otherItalic",  o.otherItalic());
                    obj.addProperty("opClock",      o.opClock());
                }
                case AddCommentOp o -> {
                    obj.addProperty("type",      "AddComment");
                    obj.add("commentId", idJson(o.commentId()));
                    obj.add("blockId",   idJson(o.blockId()));
                    obj.add("charId",    idJson(o.charId()));
                    obj.addProperty("text",     o.text());
                    obj.addProperty("authorId", o.authorId());
                    obj.addProperty("opClock",  o.opClock());
                }
                case RemoveCommentOp o -> {
                    obj.addProperty("type",    "RemoveComment");
                    obj.add("commentId", idJson(o.commentId()));
                    obj.addProperty("opClock", o.opClock());
                }
                default -> throw new IllegalArgumentException("Unknown operation type: " + op.getClass());
            }
            return obj;
        }

        // deserialize — "type" discriminator -> record
        @Override
        public Operation deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext ctx)
                throws JsonParseException {
            JsonObject obj = json.getAsJsonObject();

            JsonElement typeEl = obj.get("type");
            if (typeEl == null || typeEl.isJsonNull()) {
                throw new JsonParseException(
                        "Op JSON is missing the 'type' field. Full op: " + json);
            }
            String type = typeEl.getAsString();

            return switch (type) {
                case "CharInsert" -> {

                    JsonElement valEl = obj.get("value");
                    if (valEl == null || valEl.isJsonNull()) {
                        throw new JsonParseException(
                                "CharInsert op missing 'value'. Full op: " + obj);
                    }
                    yield new CharInsertOp(
                            parseId(obj.get("blockId")),
                            parseId(obj.get("charId")),
                            nullableId(obj.get("parentId")),
                            valEl.getAsString().charAt(0),
                            getBool(obj, "bold",   false),
                            getBool(obj, "italic", false)
                    );
                }
                case "CharDelete" -> new CharDeleteOp(
                        parseId(obj.get("blockId")),
                        parseId(obj.get("charId")),
                        getInt(obj, "opClock", 0)
                );
                case "CharUndelete" -> new CharUndeleteOp(
                        parseId(obj.get("blockId")),
                        parseId(obj.get("charId")),
                        getInt(obj, "opClock", 0)
                );
                case "Format" -> new FormatOp(
                        parseId(obj.get("blockId")),
                        parseId(obj.get("charId")),
                        getBool(obj, "bold",    false),
                        getBool(obj, "italic",  false),
                        getInt(obj,  "opClock", 0)
                );
                case "BlockInsert" -> new BlockInsertOp(
                        parseId(obj.get("blockId")),
                        nullableId(obj.get("parentId"))
                );
                case "BlockDelete" -> new BlockDeleteOp(
                        parseId(obj.get("blockId")),
                        getInt(obj, "opClock", 0)
                );
                case "BlockSplit" -> new BlockSplitOp(
                        parseId(obj.get("originalBlockId")),
                        parseId(obj.get("newBlockId")),
                        getInt(obj, "splitAfterIndex", 0),
                        getInt(obj, "opClock",         0)
                );
                case "FormatRestore" -> new FormatRestoreOp(
                        parseId(obj.get("blockId")),
                        parseId(obj.get("charId")),
                        getBool(obj, "targetBold",   false),
                        getBool(obj, "targetItalic", false),
                        getBool(obj, "otherBold",    false),
                        getBool(obj, "otherItalic",  false),
                        getInt(obj,  "opClock",      0)
                );
                case "AddComment" -> {
                    JsonElement textEl = obj.get("text");
                    yield new AddCommentOp(
                            parseId(obj.get("commentId")),
                            parseId(obj.get("blockId")),
                            parseId(obj.get("charId")),
                            textEl != null && !textEl.isJsonNull() ? textEl.getAsString() : "",
                            getInt(obj, "authorId", 0),
                            getInt(obj, "opClock",  0)
                    );
                }
                case "RemoveComment" -> new RemoveCommentOp(
                        parseId(obj.get("commentId")),
                        getInt(obj, "opClock", 0)
                );
                default -> throw new JsonParseException("Unknown operation type: " + type);
            };
        }

        // idJson — CrdtId -> {userId, clock}
        private static JsonObject idJson(CrdtId id) {
            JsonObject o = new JsonObject();
            o.addProperty("userId", id.userId);
            o.addProperty("clock",  id.clock);
            return o;
        }

        // parseId — JSON object -> CrdtId
        private static CrdtId parseId(JsonElement el) {
            JsonObject o = el.getAsJsonObject();
            return new CrdtId(o.get("userId").getAsInt(), o.get("clock").getAsInt());
        }

        // nullableId — null JSON -> null id
        private static CrdtId nullableId(JsonElement el) {
            return (el == null || el.isJsonNull()) ? null : parseId(el);
        }

        // getInt — optional int field + default
        private static int getInt(JsonObject obj, String key, int defaultVal) {
            JsonElement el = obj.get(key);
            return (el == null || el.isJsonNull()) ? defaultVal : el.getAsInt();
        }

        // getBool — optional bool field + default
        private static boolean getBool(JsonObject obj, String key, boolean defaultVal) {
            JsonElement el = obj.get(key);
            return (el == null || el.isJsonNull()) ? defaultVal : el.getAsBoolean();
        }
    }
}
