package me.abdiskiosk.minecraftaibuilder.common;

/**
 * Names shared by the plugin's relay client and the panel (panel/lib/server/hub.ts, which must stay in step). One
 * WebSocket per server carries JSON text frames, each an object with a {@code t} (type) field:
 *
 * <pre>
 * plugin → panel  {"t":"hello","protocol":1,"plugin":"0.1.0","mc":"1.21.11","bridge":"...","worlds":[...]}
 * panel → plugin  {"t":"mcp","id":7,"user":12,"assetKey":"<opaque grant>","body":{...JSON-RPC...}}
 *                 {"t":"api","id":7,"user":12,"assetKey":"<opaque grant>","tool":"fill","args":{...}}
 *                 {"t":"history","id":7,"op":"list|get|render|revert","args":{...}}
 *                 {"t":"draft","id":7,"op":"list|get|render|approve|reject|export|import","args":{...}}
 *                 {"t":"map","id":7,"op":"index|tile|area|unarea","args":{...}}   (only when hello said "map":true)
 *                 {"t":"lessons","id":7,"op":"list|add|edit|delete|seen|feedback","args":{...}}   (only when hello said "lessons":true)
 * plugin → panel  {"t":"reply","id":7,"status":200,"body":{...}}   (body null with status 202: nothing to send)
 *                 {"t":"event","event":"job.start|job.progress|job.end","job":{...}}
 *                 {"t":"event","event":"draft.update","draft":{...summary...}}
 * </pre>
 *
 * When the panel keeps history in object storage, it also answers requests the plugin makes, so a history entry
 * outlives the server's own copy. The plugin never holds storage credentials: it PUTs to the URLs it is given.
 *
 * <pre>
 * panel → plugin  {"t":"welcome","archive":true,"assets":true}   (after hello)
 * plugin → panel  {"t":"upload","id":3,"kind":"history","entry":"h1x","files":[{"name":"diff","size":812},...]}
 * panel → plugin  {"t":"answer","id":3,"status":200,"body":{"urls":{"diff":"https://...",...}}}
 * plugin → panel  {"t":"uploaded","id":4,"kind":"history","entry":"h1x","detail":{...entry, stats, types, changes...}}
 * panel → plugin  {"t":"answer","id":4,"status":200,"body":{}}
 * </pre>
 *
 * Personal assets use additive frames (no protocol bump). The grant is scoped to one authenticated request on
 * this socket and revoked on reply, timeout or disconnect. User ids in plugin frames do not authorize access.
 * <pre>
 * plugin → panel  {"t":"asset","id":5,"assetKey":"...","op":"list|get|check|save","args":{...}}
 * panel → plugin  {"t":"answer","id":5,"status":200,"body":{...}}
 * </pre>
 * List returns up to 30 {name,size,description}. Get takes name and returns {id,size,bytes,sha256,url}; the download
 * URL expires after 60 seconds. Check preflights a new name/quota. Save takes {name,description,size,data}, where
 * data is base64 StateArrays (at most 5 MB compressed, 2 million cells); ownership comes only from the grant.
 * Without welcome.assets (no object storage, or an older panel) there is no library: calls keep server-local
 * blueprints even with a user, and the panel sends none then. A call with a user but no assetKey (the panel had too
 * many calls open) runs, but its asset operations fail.
 *
 * Players link their Minecraft account to a panel account and save assets in game with additive frames (no protocol
 * bump), offered when the welcome says {@code "links":true}. They are authorized by the player's link and the linked
 * account's builder role on this socket's server, never by a grant or a user id the plugin sends; the code is a
 * one-time credential the player made on the panel.
 * <pre>
 * plugin → panel  {"t":"link","id":6,"code":"ABCD-EFGH","player":{"uuid":"...","name":"Alex"}}
 *                 {"t":"unlink","id":7,"player":{"uuid":"...","name":"Alex"}}
 *                 {"t":"player_asset","id":8,"op":"check|save","player":{"uuid":"...","name":"Alex"},"args":{...}}
 * panel → plugin  {"t":"answer","id":6,"status":200,"body":{"linked":true,"already":false}}
 * </pre>
 * player_asset takes the args of the asset frame's check and save. An older panel sends no "links" and the plugin then
 * sends none of these frames.
 *
 * Without a welcome that says {@code "archive":true}, the plugin keeps its entries and asks for nothing.
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
    public static final String MAP = "map";
    public static final String LESSONS = "lessons";
    public static final String REPLY = "reply";
    public static final String EVENT = "event";
    public static final String WELCOME = "welcome";
    public static final String UPLOAD = "upload";
    public static final String UPLOADED = "uploaded";
    public static final String ANSWER = "answer";
    public static final String ASSET = "asset";
    public static final String LINK = "link";
    public static final String UNLINK = "unlink";
    public static final String PLAYER_ASSET = "player_asset";

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
