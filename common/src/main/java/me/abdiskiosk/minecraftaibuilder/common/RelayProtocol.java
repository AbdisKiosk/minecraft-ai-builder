package me.abdiskiosk.minecraftaibuilder.common;

/**
 * Names shared by the plugin's relay client and the web panel. One WebSocket per server carries JSON text frames,
 * each an object with a {@code t} (type) field:
 *
 * <pre>
 * plugin → panel  {"t":"hello","protocol":1,"plugin":"0.1.0","mc":"1.21.11","bridge":"...","worlds":[...]}
 * panel → plugin  {"t":"mcp","id":7,"body":{...JSON-RPC...}}
 *                 {"t":"api","id":7,"tool":"fill","args":{...}}
 *                 {"t":"history","id":7,"op":"list|get|render|revert","args":{...}}
 *                 {"t":"draft","id":7,"op":"list|get|render|approve|reject|export|import","args":{...}}
 * plugin → panel  {"t":"reply","id":7,"status":200,"body":{...}}   (body null with status 202: nothing to send)
 *                 {"t":"event","event":"job.start|job.progress|job.end","job":{...}}
 *                 {"t":"event","event":"draft.update","draft":{...summary...}}
 * </pre>
 *
 * The panel knows which server a socket belongs to from the bearer token it connected with.
 */
public final class RelayProtocol {

    /** Bumped on any incompatible change; the panel refuses a plugin that sends another number. */
    public static final int VERSION = 2;

    public static final String PATH = "/relay";

    public static final String HELLO = "hello";
    public static final String MCP = "mcp";
    public static final String API = "api";
    public static final String HISTORY = "history";
    public static final String DRAFT = "draft";
    public static final String REPLY = "reply";
    public static final String EVENT = "event";

    public static final String JOB_START = "job.start";
    public static final String JOB_PROGRESS = "job.progress";
    public static final String JOB_END = "job.end";
    public static final String DRAFT_UPDATE = "draft.update";

    /** WebSocket close codes the panel uses; the plugin logs the reason and, except for these, retries. */
    public static final int CLOSE_BAD_TOKEN = 4001;
    public static final int CLOSE_BAD_PROTOCOL = 4002;
    /** Another connection with the same server token took over. */
    public static final int CLOSE_REPLACED = 4003;

    private RelayProtocol() {
    }
}
