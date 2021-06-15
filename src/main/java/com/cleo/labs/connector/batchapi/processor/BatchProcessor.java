package com.cleo.labs.connector.batchapi.processor;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.cleo.labs.connector.batchapi.processor.template.CsvExpander;
import com.cleo.labs.connector.batchapi.processor.template.TemplateExpander;
import com.cleo.labs.connector.batchapi.processor.template.TemplateExpander.Expander;
import com.cleo.labs.connector.batchapi.processor.versalex.StubVersaLex;
import com.cleo.labs.connector.batchapi.processor.versalex.VersaLex;
import com.fasterxml.jackson.core.JsonGenerator.Feature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SequenceWriter;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.base.Charsets;
import com.google.common.base.Strings;
import com.google.common.io.ByteStreams;

public class BatchProcessor {
    public enum Operation {
        add ("created"),
        list ("found"),
        update ("updating"),
        delete ("deleted"),
        preview ("previewed"),
        run ("run");

        private String tag;
        private Operation(String tag) {
            this.tag = tag;
        }
        public String tag() {
            return tag;
        }
    };

    public enum OutputFormat {yaml, json, csv};

    private ApiClientFactory factory;
    private ApiClient api;
    private String exportPassword;
    private Operation defaultOperation;
    private String template;
    private boolean traceRequests;
    private boolean generatePasswords;
    private OutputFormat outputFormat;
    private String outputTemplate;
    private Path logOutput;
    private VersaLex versalex;
    private boolean csvInput; // set to true if CSV input is found

    public BatchProcessor setExportPassword(String exportPassword) {
        this.exportPassword = exportPassword;
        return this;
    }

    public BatchProcessor setDefaultOperation(Operation defaultOperation) {
        if (defaultOperation != null) {
            // null means "leave the same": add by default
            this.defaultOperation = defaultOperation;
        }
        return this;
    }

    public BatchProcessor setTemplate(Path template) throws IOException {
        return setTemplate(new String(Files.readAllBytes(template), Charsets.UTF_8));
    }

    public BatchProcessor setTemplate(String template) {
        this.template = template;
        return this;
    }

    public BatchProcessor setTraceRequests(boolean traceRequests) {
        this.traceRequests = traceRequests;
        return this;
    }

    public BatchProcessor setGeneratePasswords(boolean generatePasswords) {
        this.generatePasswords = generatePasswords;
        return this;
    }

    public BatchProcessor setOutputFormat(OutputFormat outputFormat) {
        if (outputFormat != null) {
            // null means "leave the same": yaml by default
            this.outputFormat = outputFormat;
        }
        return this;
    }

    public BatchProcessor setOutputTemplate(Path outputTemplate) throws IOException {
        return setOutputTemplate(new String(Files.readAllBytes(outputTemplate), Charsets.UTF_8));
    }

    public BatchProcessor setOutputTemplate(String outputTemplate) {
        this.outputTemplate = outputTemplate;
        return this;
    }

    public BatchProcessor setLogOutput(Path logOutput) {
        this.logOutput = logOutput;
        return this;
    }

    private void loadVersaLex() {
        try {
            Class<?> clazz = Class.forName("com.cleo.labs.connector.batchapi.processor.versalex.RealVersaLex");
            Object object = clazz.newInstance();
            versalex = VersaLex.class.cast(object);
            versalex.connect();
        } catch (Throwable e) {
            versalex = new StubVersaLex();
            versalex.connect();
        }
    }

    public void close() {
        if (versalex != null) {
            versalex.disconnect();
        }
    }

    public enum ResourceClass {
        user ("username"),
        authenticator ("authenticator"),
        connection ("connection"),
        any ("name");

        private String tag;
        private ResourceClass(String tag) {
            this.tag = tag;
        }
        public String tag() {
            return tag;
        }
    };

    public enum AuthenticatorType {nativeUser, systemLdap, authConnector, authenticator};
    private static final Set<String> AUTH_TYPES = EnumSet.allOf(AuthenticatorType.class)
            .stream()
            .map(AuthenticatorType::name)
            .collect(Collectors.toSet());
    public enum ActionType {Commands, JavaScript};
    private static final Set<String> ACTION_TYPES = EnumSet.allOf(ActionType.class)
            .stream()
            .map(ActionType::name)
            .collect(Collectors.toSet());

    private Map<String,ApiClient> apiClientCache = new HashMap<>();
    private void setApi(String profileName) throws Exception {
        api = apiClientCache.get(profileName);
        if (api == null) {
            api = factory.getApiClient(profileName);
            apiClientCache.put(profileName, api);
        }
    }

	/*------------------------------------------------------------------------*
	 * For bulk user endpoints we want to avoid looking up authenticators for *
	 * the users, so we maintain a cache (by authenticator alias/name and     *
	 * HATEOAS link) of authenticators to reduce the noise).                  *
	 *------------------------------------------------------------------------*/

    private Map<String, ObjectNode> authenticatorNameCache = new HashMap<>();
    private Map<String, ObjectNode> authenticatorLinkCache = new HashMap<>();

    private ObjectNode getAuthenticatorByName(String alias) throws Exception {
        ObjectNode authenticator;
        if (authenticatorNameCache.containsKey(alias)) {
            authenticator = authenticatorNameCache.get(alias);
        } else {
            authenticator = api.getAuthenticator(alias);
            if (authenticator != null) {
                authenticatorNameCache.put(alias, authenticator);
                authenticatorLinkCache.put(Json.getHref(authenticator), authenticator);
            }
        }
        return authenticator;
    }

    private ObjectNode getAuthenticatorByLink(String link) throws Exception {
        ObjectNode authenticator;
        if (authenticatorLinkCache.containsKey(link)) {
            authenticator = authenticatorLinkCache.get(link);
        } else {
            authenticator = api.get(link);
            if (authenticator != null) {
                authenticatorNameCache.put(Json.getSubElementAsText(authenticator, "alias"), authenticator);
                authenticatorLinkCache.put(Json.getHref(authenticator), authenticator);
            }
        }
        return authenticator;
    }

    /**
     * Add {@code actions} to {@code resource}. {@code actions} are in Batch
     * format, taken directly from the request entry. {@code resource} is in
     * Official format (e.g. the response from an add or for an update, a
     * get with injected actions).
     * <p/>
     * The actions are compared with actions attached to {@code resource}: any
     * new actions are added, any {@code operation: delete} actions are deleted,
     * and any other actions are left alone. Returns the updated {@code actions}
     * object in Batch format.
     * @param actions
     * @param resource
     * @return
     * @throws Exception
     */
    private ObjectNode createActions(ObjectNode actions, ObjectNode resource) throws Exception {
        ObjectNode updated = (ObjectNode)resource.get(ACTIONSTOKEN);
        if (actions != null && actions.size() > 0) {
            if (updated == null) {
                updated = Json.mapper.createObjectNode();
            } else {
                updated = updated.deepCopy();
            }
            String type = Json.getSubElementAsText(resource, "meta.resourceType");
            for (JsonNode element : actions) {
                if (element != null && element.isObject()) {
                    ObjectNode action = actionBatch2Official((ObjectNode)element);
                    String actionName = Json.getSubElementAsText(action, "alias", "");
                    if (!actionName.isEmpty() && !actionName.equals("NA")) {
                        action.put("enabled", true);
                        if (!action.has("type")) {
                            action.put("type", "Commands");
                        }
                        switch (type) {
                        case "user":
                            action.putObject("authenticator")
                                    .put("href", Json.getSubElementAsText(resource, "_links.authenticator.href"))
                                    .putObject("user").put("href", Json.getHref(resource));
                            break;
                        case "authenticator":
                            action.putObject("authenticator").put("href", Json.getHref(resource));
                            break;
                        default:
                        }
                        action.putObject("connection").put("href", Json.getHref(resource));
                        String schedule = Json.getSubElementAsText(action, "schedule", "");
                        if (schedule.isEmpty() || schedule.equals("none") || schedule.equals("no")) {
                            action.remove("schedule");
                        }
                        Operation operation = Operation.valueOf(Json.asText(action.remove("operation"), "add"));
                        JsonNode existing = updated.get(actionName);
                        if (existing == null) {
                            if (!operation.equals(Operation.delete)) {
                                ObjectNode newAction = api.createAction(action);
                                updated.set(actionName, newAction);
                            }
                        } else {
                            if (operation.equals(Operation.delete)) {
                                api.delete(existing);
                                updated.remove(actionName);
                            } else {
                                ObjectNode newAction = api.put(actions, existing);
                                updated.replace(actionName, newAction);
                            }
                        }
                    }
                }
            }
            if (updated.size() > 0) {
                resource.set(ACTIONSTOKEN, updated);
            } else {
                resource.remove(ACTIONSTOKEN);
            }
        }
        return updated;
    }

    /**
     * Updates an Official form {@code original} (as returned from {@link #processList})
     * with requested updates in Batch form (taken from {@code request.entry}).
     * @param original the Official form object to update
     * @param updates the Batch form updates
     * @return the updated object in Official form (next step: call {@link #createActions})
     * @throws Exception
     */
    private ObjectNode updateResource(ObjectNode original, ObjectNode updates) throws Exception {
        String type = Json.getSubElementAsText(original, "meta.resourceType", "");
        ObjectNode updated = Json.removeElements(officialCleanup(original.deepCopy()),
                ACTIONSTOKEN);
        ObjectNode officialUpdates;
        String pwdhash = null;
        String password = null;
        if (type.equals("user")) {
            officialUpdates = userBatch2Official(updates);
            pwdhash = Json.getSubElementAsText(updates, "pwdhash");
            // official get/post/put responses never have a pwdhash
            // if updates has a password, we'll save it for later
        } else if (type.equals("connection")) {
            officialUpdates = connectionBatch2Official(updates);
            password = Json.getSubElementAsText(officialUpdates, "connect.password");
            // official get/post/put responses never have a password
            // if updates has a password, it might be encrypted:
            //   connectionBatch2Official takes care of decrypting it
        } else if (type.equals("action")) {
            officialUpdates = actionBatch2Official(updates);
        } else if (type.equals("authenticator")) {
            officialUpdates = authenticatorBatch2Official(updates);
        } else {
            throw new ProcessingException("can't update "+original.toString());
        }
        Json.mergeUpdates(updated, officialUpdates);
        //cleanup(updated);
        ObjectNode officialResult = api.put(updated, original);
        if (type.equals("user")) {
            ObjectNode batchResult = userOfficial2Batch(officialResult);
            if (pwdhash != null) {
                versalex.set(Json.getSubElementAsText(batchResult, "authenticator"),
                        Json.getSubElementAsText(batchResult, "username"), "Pwdhash", pwdhash);
            }
        } else if (type.equals("connection")) {
            if (password != null) {
                // save it into the result (in the clear -- connectionOfficial2Batch will encrypt)
                Json.setSubElement(officialResult, "connect.password", password);
            }
        }
        return officialResult;
    }

    /**
     * A special variant of {@link #updateResource} that allows the {@code authenticator}
     * to be "updated": technically this requires deleting the user from its current
     * authenticator and then added to the new authenticator.
     * <p/>
     * {@code original} is still in Official form (as returned from {@link #processList})
     * with requested updates in Batch form (taken from {@code request.entry}).
     * @param original the Official form user to update
     * @param updates the Batch form updates
     * @return the updated object in Official form (next step: call {@link #createActions})
     * @throws Exception
     */
    private ObjectNode moveUser(ObjectNode original, ObjectNode updates) throws Exception {
        // set up a new add request based on the original:
        //   convert from Official back to Batch form...
        //   and then digest it like analyzeRequest would.
        Request add = new Request();
        add.operation = Operation.add;
        add.resource = Json.getSubElementAsText(original, "username");
        add.resourceClass = ResourceClass.user;
        add.resourceType = "user";
        add.entry = userOfficial2Batch(original);

        // merge in the updates: note we are in Batch form
        ObjectNode updateActions = (ObjectNode)updates.remove("actions");
        Json.mergeUpdates(add.entry, updates);
        if (updateActions != null && updateActions.size() > 0) {
            ObjectNode entryActions = (ObjectNode)add.entry.get("actions");
            if (entryActions == null || entryActions.size() == 0) {
                add.entry.set("actions", updateActions);
            } else {
                Iterator<String> fields = updateActions.fieldNames();
                while (fields.hasNext()) {
                    String action = fields.next();
                    entryActions.set(action, updateActions.get(action));
                }
            }
        }
        // finally split out the actions
        add.actions = (ObjectNode)add.entry.remove("actions");

        // now delete the original
        api.delete(original); // and add the update
        List<ObjectNode> tempResults = new ArrayList<>();
        ArrayNode tempPasswords = Json.mapper.createArrayNode();
        processAddUser(add, tempResults, tempPasswords);
        return (ObjectNode)tempResults.get(0);
    }

    private String getObjectName(ObjectNode object) {
        String alias = Json.getSubElementAsText(object, "username");
        if (alias == null) {
            alias = Json.getSubElementAsText(object, "authenticator");
            if (alias == null) {
                alias = Json.getSubElementAsText(object, "connection");
                if (alias == null) {
                    alias = Json.getSubElementAsText(object, "action");
                }
            }
        }
        return alias;
    }

    /**
     * Converts a Batch form "actions" field into a normalized form.
     * "actions" in a Batch form field is accepted as either:
     * <ul><li>an Object whose field names are action aliases with
     *         values being the actions themselves, or</li>
     *     <li>an Array of actions (each with an "action" name)</li></ul>
     * This method converts the Array form into the Object form.
     * @param actions a Batch form "actions" field: {@code null} or {@code ObjectNode} or {@code ArrayNode}
     * @return {@code null} or a normalized {@code ObjectNode}
     * @throws Exception
     */
    private ObjectNode normalizeActions(JsonNode actions) throws Exception {
        if (actions != null && actions.isArray()) {
            // convert from [ {alias=x, ...}, {alias=y, ...} ] to { x:{alias=x, ...},
            // y:{alias=y, ...} }
            ObjectNode map = Json.mapper.createObjectNode();
            for (JsonNode action : actions) {
                String alias = Json.getSubElementAsText(action, "action");
                if (alias == null) {
                    throw new ProcessingException("action found without \"action\" name");
                }
                map.set(alias, action);
            }
            actions = map;
        }
        return (ObjectNode) actions;
    }

	/*------------------------------------------------------------------------*
	 * Conversions                                                            *
	 *                                                                        *
	 * Each object type (user, authenticator, connection, action) exists in   *
	 * three forms:                                                           *
	 *   Official form -- the JSON as returned from a GET or POST to the API  *
	 *   Official Cleaned Up form -- Official with read-only and some common  *
	 *                               defaults removed                         *
	 *   Batch form -- the altered form used in requests and results, with    *
	 *                 fields like "alias" mapped to "connection" or "action" *
	 *                 or "authenticator", among other things                 *
	 *                                                                        *
	 * Each type has three conversion methods:                                *
	 *   <type>OfficialCleanup - strips the read-only fields and some common  *
	 *                           defaults, but keeps the object in Official   *
	 *                           form (meaning POST or PUT-able on the API).  *
	 *   <type>Official2Batch  - converts from Official form to Batch form,   *
	 *                           converting id-based links to names,          *
	 *                           denormalizing actions, generally improving   *
	 *                           readability, but not POST or PUT-able.       *
	 *   <type>Batch2Official  - converts from Batch form to Official form,   *
	 *                           making the content POST or PUT-able and      *
	 *                           removing readbility items.                   *
	 *------------------------------------------------------------------------*/

	/*- user -----------------------------------------------------------------*/

    /**
     * Strips the read-only fields and some defaults from an official API export
     * of a user. The node is modified in place, not copied.
     * The original (modified) node is returned.
     * @param official the official API export to strip of read-only fields
     * @return the original node, stripped
     */
    private ObjectNode userOfficialCleanup(ObjectNode official) {
        return Json.removeElements(official,
                "active",
                "editable",
                "runnable",
                "ready",
                "enabled=true",
                "home.dir.default",
                "home.subfolders.default",
                "accept.lastPasswordReset",
                "accept.sftp.auth=[{\"type\":\"userPwd\"}]",
                "outgoing.partnerPackaging=false",
                "incoming.partnerPackaging=false",
                "meta",
                "_links");
    }

    /**
     * Converts from Official form Batch form, returning a newly created
     * ObjectNode to hold the results. The original Official ObjectNode is left
     * unharmed.
     * @param official the Office form object
     * @return the Batch form object
     * @throws Exception
     */
    private ObjectNode userOfficial2Batch(ObjectNode official) throws Exception {
        ObjectNode batch = Json.mapper.createObjectNode();
        // copy id, username, email to the top of the list
        batch.set("id", official.get("id"));
        String username = Json.getSubElementAsText(official, "username");
        batch.put("username", username);
        batch.set("email",  official.get("email"));
        // inject Authenticator
        JsonNode authenticatorlink = official.path("_links").path("authenticator");
        if (!authenticatorlink.isMissingNode()) {
            ObjectNode authenticator = getAuthenticatorByLink(Json.getSubElementAsText(authenticatorlink, "href"));
            String alias = Json.getSubElementAsText(authenticator, "alias");
            String pwdhash = versalex.get(alias, username, "Pwdhash");
            if (alias != null) {
                // set host, but reorder things to get it near the top
                batch.put("authenticator", alias);
                if (!Strings.isNullOrEmpty(pwdhash)) {
                    batch.put("pwdhash", pwdhash);
                }
            }
        }
        batch.setAll(Json.removeElements(userOfficialCleanup(official.deepCopy()),
                ACTIONSTOKEN));
        actionsCopyOfficial2Batch(official, batch);
        return batch;
    }

    /**
     * Converts from Batch form Official form, returning a newly created
     * ObjectNode to hold the results. The original Batch ObjectNode is left
     * unharmed.
     * @param batch the Batch form object
     * @return the Official form object
     * @throws Exception
     */
    private ObjectNode userBatch2Official(ObjectNode batch) {
        ObjectNode official = Json.mapper.createObjectNode();
        //Json.setSubElement(official, "alias", Json.getSubElement(batch, "action"));
        official.setAll(Json.removeElements(batch.deepCopy(),
                "actions",
                "authenticator",
                "pwdhash"));
        return official;
    }

	/*- authenticator --------------------------------------------------------*/

    /**
     * Strips the read-only fields and some defaults from an official API export
     * of an authenticator. The node is modified in place, not copied.
     * The original (modified) node is returned.
     * @param official the official API export to strip of read-only fields
     * @return the original node, stripped
     */
    private ObjectNode authenticatorOfficialCleanup(ObjectNode official) {
        return Json.removeElements(official,
                "active",
                "editable",
                "runnable",
                "ready=true",
                "enabled=true",
                "home.enabled=true",
                "home.dir.default=local/root/%username%",
                "home.subfolders.default=---\n- usage: download\n  path: inbox\\\n- usage: upload\n  path: outbox\\\n",
                "home.access=file",
                "privileges.transfers.view=true",
                "privileges.unify.enabled=false",
                "privileges.invitations.enabled=false",
                "privileges.twoFactorAuthentication.enabled=false",
                "incoming.filters.fileNamesPattern=\"*\"",
                "accept.security.requireIPFilter=false",
                "accept.security.passwordRules.enforce=false",
                "accept.security.passwordRules.minLength=8",
                "accept.security.passwordRules.cannotContainUserName=true",
                "accept.security.passwordRules.minUpperChars=1",
                "accept.security.passwordRules.minLowerChars=1",
                "accept.security.passwordRules.minNumericChars=1",
                "accept.security.passwordRules.minSpecialChars=1",
                "accept.security.passwordRules.noRepetitionCount=3",
                "accept.security.passwordRules.requirePasswordResetBeforeFirstUse=false",
                "accept.security.passwordRules.expiration.enabled=true",
                "accept.security.passwordRules.expiration.expiresDays=60",
                "accept.security.passwordRules.lockout.enabled=false",
                "accept.security.passwordRules.lockout.afterFailedAttempts=5",
                "accept.security.passwordRules.lockout.withinSeconds=60",
                "accept.security.passwordRules.lockout.lockoutMinutes=15",
                "accept.ftp.enabled=true",
                "accept.ftp.passiveModeUseExternalIP=false",
                "accept.ftp.autoDeleteDownloadedFile=false",
                "accept.ftp.activeModeSourcePort=0",
                "accept.ftp.ignoreDisconnectWithoutQuit=false",
                "accept.ftp.triggerAtUpload=false",
                "accept.sftp.enabled=true",
                "accept.sftp.prefixHome=false",
                "accept.http.enabled=true",
                "accept.requireSecurePort=false",
                "meta",
                "_links");
    }

    /**
     * Converts from Official form Batch form, returning a newly created
     * ObjectNode to hold the results. The original Official ObjectNode is left
     * unharmed.
     * @param official the Office form object
     * @return the Batch form object
     * @throws Exception
     */
    private ObjectNode authenticatorOfficial2Batch(ObjectNode official) {
        ObjectNode batch = Json.mapper.createObjectNode();
        // rename "alias" to "authenticator"
        Json.setSubElement(batch, "authenticator", Json.getSubElementAsText(official, "alias"));
        batch.setAll(Json.removeElements(authenticatorOfficialCleanup(official.deepCopy()),
                "alias",   // renamed to "authenticator" above
                ACTIONSTOKEN)); // keep USERSTOKEN for expansion/removal in appendAndFlattenUsers
        actionsCopyOfficial2Batch(official, batch);
        return batch;
    }

    /**
     * Converts from Batch form Official form, returning a newly created
     * ObjectNode to hold the results. The original Batch ObjectNode is left
     * unharmed.
     * @param batch the Batch form object
     * @return the Official form object
     * @throws Exception
     */
    private ObjectNode authenticatorBatch2Official(ObjectNode batch) {
        ObjectNode official = Json.mapper.createObjectNode();
        Json.setSubElement(official, "alias", Json.getSubElement(batch, "authenticator"));
        official.setAll(Json.removeElements(batch.deepCopy(),
                "actions",
                "authenticator"));
        return official;
    }

	/*- connection -----------------------------------------------------------*/

    /**
     * Strips the read-only fields and some defaults from an official API export
     * of a connection. The node is modified in place, not copied.
     * The original (modified) node is returned.
     * @param official the official API export to strip of read-only fields
     * @return the original node, stripped
     */
    private ObjectNode connectionOfficialCleanup(ObjectNode official) {
        return Json.removeElements(official,
                "active",
                "editable",
                "runnable",
                "ready=true",
                "enabled=true",
                "meta",
                "_links");
    }

    /**
     * Converts from Official form Batch form, returning a newly created
     * ObjectNode to hold the results. The original Official ObjectNode is left
     * unharmed.
     * @param official the Office form object
     * @return the Batch form object
     * @throws Exception
     */
    private ObjectNode connectionOfficial2Batch(ObjectNode official) throws Exception {
        ObjectNode batch = Json.mapper.createObjectNode();
        // set up the reference links as first class field and rename "alias" to "connection"
        String alias = Json.getSubElementAsText(official, "alias");
        Json.setSubElement(batch, "connection", alias);
        // try to get the password, and then try to encrypt it
        String password = versalex.decrypt(versalex.get(alias, "password"));
        if (!Strings.isNullOrEmpty(password)) {
            Json.setSubElement(batch, "connect.password", OpenSSLCrypt.encrypt(exportPassword, password));
        }
        batch.setAll(Json.removeElements(connectionOfficialCleanup(official.deepCopy()),
                "alias",   // renamed to "connection" above
                ACTIONSTOKEN));
        certsExpandOfficial2Batch(batch);
        actionsCopyOfficial2Batch(official, batch);
        return batch;
    }

    /**
     * Converts from Batch form Official form, returning a newly created
     * ObjectNode to hold the results. The original Batch ObjectNode is left
     * unharmed.
     * @param batch the Batch form object
     * @return the Official form object
     * @throws Exception
     */
    private ObjectNode connectionBatch2Official(ObjectNode batch) throws Exception {
        ObjectNode official = Json.mapper.createObjectNode();
        Json.setSubElement(official, "alias", Json.getSubElement(batch, "connection"));
        official.setAll(Json.removeElements(batch.deepCopy(),
                "actions",
                "connection"));
        String password = Json.getSubElementAsText(batch, "connect.password");
        if (password != null) {
            Json.setSubElement(official, "connect.password", versalex.decrypt(password));
        }
        certsImportBatch2Official(official);
        return official;
    }

	/*- action ---------------------------------------------------------------*/

    /**
     * Strips the read-only fields and some defaults from an official API export
     * of an action. The node is modified in place, not copied.
     * The original (modified) node is returned.
     * @param official the official API export to strip of read-only fields
     * @return the original node, stripped
     */
    private ObjectNode actionOfficialCleanup(ObjectNode official) {
        return Json.removeElements(official,
                "active",
                "editable",
                "runnable",
                "running",
                "ready",
                "notReadyReason",
                "nextRun",
                "lastStatus",
                "enabled=true",
                "authenticator",
                "connection",
                "meta",
                "_links");
    }

    /**
     * Converts from Official form Batch form, returning a newly created
     * ObjectNode to hold the results. The original Official ObjectNode is left
     * unharmed.
     * @param official the Office form object
     * @return the Batch form object
     * @throws Exception
     */
    private ObjectNode actionOfficial2Batch(ObjectNode official) {
        ObjectNode batch = Json.mapper.createObjectNode();
        // set up the reference links as first class field and rename "alias" to "action"
        Json.setSubElement(batch, "action", Json.getSubElementAsText(official, "alias"));
        Json.setSubElement(batch, "username", Json.getSubElementAsText(official, "authenticator.user.username"));
        Json.setSubElement(batch, "authenticator", Json.getSubElementAsText(official, "authenticator.alias"));
        Json.setSubElement(batch, "connection", Json.getSubElementAsText(official, "connection.alias"));
        batch.setAll(Json.removeElements(actionOfficialCleanup(official.deepCopy()),
                "alias")); // renamed to "action" above
        return batch;
    }

    /**
     * Converts from Batch form Official form, returning a newly created
     * ObjectNode to hold the results. The original Batch ObjectNode is left
     * unharmed.
     * @param batch the Batch form object
     * @return the Official form object
     * @throws Exception
     */
    private ObjectNode actionBatch2Official(ObjectNode batch) {
        ObjectNode official = Json.mapper.createObjectNode();
        Json.setSubElement(official, "alias", Json.getSubElement(batch, "action"));
        official.setAll(Json.removeElements(batch.deepCopy(),
                "action",
                "username",
                "authenticator",
                "connection"));
        return official;
    }

    /**
     * Copies Official form actions from the ACTIONSTOKEN field injected into an
     * Official form object into Batch form actions injected into the "actions"
     * field of an existing Batch form object.
     * @param official the Official form object, which may have an ACTIONSTOKEN
     * @param batch the Batch form object, into which "actions" may be injected
     */
    private void actionsCopyOfficial2Batch(ObjectNode official, ObjectNode batch) {
        if (official.has(ACTIONSTOKEN)) {
            ObjectNode officialActions = (ObjectNode)official.get(ACTIONSTOKEN);
            ObjectNode batchActions = Json.mapper.createObjectNode();
            for (JsonNode action : officialActions) {
                batchActions.set(action.get("alias").asText(), actionOfficial2Batch((ObjectNode)action));
            }
            batch.set("actions", batchActions);
        }
    }

    /**
     * Looks for Official form fields that point to certificates in a Batch form
     * object as it is being prepared and expands the certificate references into
     * the actual certificate.
     * </p>
     * A "certificate field" is one that is an ObjectNode with an "href" field
     * that looks like "/api/certs/" (note that Harmony sometimes will return an
     * "href" of just "/api/certs" when an object points to a cert that is not
     * defined, so we skip over those by including the final "/"). If a proper
     * certificate field is found, the certificate is chased from the API and
     * the certificate contents are formatted as a folded base64 cert with header
     * and trailer (see {@link CertUtils#export(String)}).
     * @param batch the Batch form object to search for certificate fields and to update
     * @throws Exception
     */
    private void certsExpandOfficial2Batch(ObjectNode batch) throws Exception {
        for (JsonNode field : batch) {
            String href = Json.getSubElementAsText(field, "href");
            if (href != null && href.startsWith("/api/certs/")) {
                ObjectNode cert = api.get(href);
                ((ObjectNode)field).put("certificate", CertUtils.export(Json.getSubElementAsText(cert, "certificate")));
            } else if (field.isObject()) {
                certsExpandOfficial2Batch((ObjectNode)field);
            }
        }
    }

    /**
     * Looks for Batch form fields that contain (public key) certificate references
     * in an Official form object as it is being prepared and converts those
     * references into the "href" form expected by the API.
     * <p/>
     * A "certificate reference" in Batch form is a field with a "certificate" field
     * inside, which contains an X509Certificate in some understandable form
     * (see {@link CertUtils#cert(byte[])}). These found certificates are imported
     * through the API and are replaced by the "href" of the imported object.
     * <p/>
     * Note that the API only allows a certificate to be imported once, so if the
     * certificate already exists the "href" will point to the existing cert.
     * @param official the Official form object to search and update
     * @throws Exception
     */
    private void certsImportBatch2Official(ObjectNode official) throws Exception {
        for (JsonNode field : official) {
            if (field.isObject()) {
                ObjectNode object = (ObjectNode)field;
                String cert = Json.getSubElementAsText(object, "certificate");
                if (cert != null) {
                    ObjectNode imported = api.importOrGetCert(object);
                    object.put("href", Json.getHref(imported));
                    object.remove("certificate");
                } else {
                    certsImportBatch2Official(object);
                }
            }
        }
    }

	/*- anything (figure it out from meta.resourceType) ----------------------*/

    /**
     * Converts from Official form Batch form, returning a newly created
     * ObjectNode to hold the results. The original Official ObjectNode is left
     * unharmed.
     * @param official the Office form object
     * @return the Batch form object
     * @throws Exception
     */
    private ObjectNode official2Batch(ObjectNode official) throws Exception {
        String type = Json.getSubElementAsText(official, "meta.resourceType", "");
        switch (type) {
        case "user":
            return userOfficial2Batch(official);
        case "authenticator":
            return authenticatorOfficial2Batch(official);
        case "connection":
            return connectionOfficial2Batch(official);
        case "action":
            return actionOfficial2Batch(official);
        default:
            return official;
        }
    }

    /**
     * Strips the read-only fields and some defaults from an official API export
     * of an object. The node is modified in place, not copied.
     * The original (modified) node is returned.
     * @param official the official API export to strip of read-only fields
     * @return the original node, stripped
     */
    private ObjectNode officialCleanup(ObjectNode official) throws Exception {
        String type = Json.getSubElementAsText(official, "meta.resourceType", "");
        switch (type) {
        case "user":
            return userOfficialCleanup(official);
        case "authenticator":
            return authenticatorOfficialCleanup(official);
        case "connection":
            return connectionOfficialCleanup(official);
        case "action":
            return actionOfficialCleanup(official);
        default:
            return official;
        }
    }

	/*------------------------------------------------------------------------*
	 * Get/List Operations                                                    *
	 *------------------------------------------------------------------------*/

    private static final String ACTIONSTOKEN = "$$actions$$";

    /**
     * Digs into an Official form object for the HATEOAS link for actions
     * and retrieves the action objects, also in Official form, into an
     * ObjectNode whose keys are the action aliases and whose values are
     * the actions themselves. This ObjectNode is injected into the Official
     * form resource under the ACTIONSTOKEN.
     * @param resource the Official form object to extend
     * @return an Official form object with Official form actions under ACTIONSTOKEN
     * @throws Exception
     */
    private ObjectNode injectActions(ObjectNode resource) throws Exception {
        JsonNode actionlinks = resource.path("_links").path("actions");
        if (!actionlinks.isMissingNode()) {
            ObjectNode actions = Json.mapper.createObjectNode();
            for (JsonNode actionlink : actionlinks) {
                ObjectNode action = api.get(Json.getSubElementAsText(actionlink, "href"));
                actions.set(Json.getSubElementAsText(action, "alias"), action);
            }
            if (actions.size() > 0) {
                resource.set(ACTIONSTOKEN, actions);
            }
        }
        return resource;
    }

    private List<ObjectNode> listUsers(Request request) throws Exception {
        List<ObjectNode> list;
        String authenticator = Json.getSubElementAsText(request.entry, "authenticator");
        if (request.resourceFilter != null) {
            String filter = request.resourceFilter.replace(NAMETOKEN, "username");
            String authfilter = Strings.isNullOrEmpty(authenticator) ? null : "alias eq \""+authenticator+"\"";
            list = api.getUsers(authfilter, filter);
            if (list.isEmpty()) {
                throw new NotFoundException("filter \""+filter+"\" returned no users"+
                    (Strings.isNullOrEmpty(authenticator) ? "" : " in "+authenticator));
            }
        } else {
            String authfilter = Strings.isNullOrEmpty(authenticator) ? null : "alias eq \""+authenticator+"\"";
            ObjectNode single = api.getUser(authfilter, request.resource);
            if (single == null) {
                throw new NotFoundException("user "+request.resource+" not found"+
                    (Strings.isNullOrEmpty(authenticator) ? "" : " in "+authenticator));
            }
            list = Arrays.asList(single);
        }
        if (request.operation != Operation.add) {
            for (ObjectNode user : list) {
                injectActions(user);
            }
        }
        return list;
    }

    private static final String USERSTOKEN = "$$users$$"; // place to stash ArrayNode of users in an authenticator result

    private List<ObjectNode> listAuthenticators(Request request, boolean includeUsers) throws Exception {
        List<ObjectNode> list;
        if (request.resourceFilter != null) {
            String filter = request.resourceFilter.replace(NAMETOKEN, "alias");
            list = api.getAuthenticators(filter);
            if (list.isEmpty()) {
                throw new NotFoundException("filter \""+filter+"\" returned no authenticators");
            }
        } else {
            ObjectNode single = api.getAuthenticator(request.resource);
            if (single == null) {
                throw new NotFoundException("authenticator "+request.resource+" not found");
            }
            list = Arrays.asList(single);
        }
        for (ObjectNode authenticator : list) {
            if (request.operation != Operation.add) {
                injectActions(authenticator);
            }
            // collect users, if requested
            if (includeUsers) {
                List<ObjectNode> userlist = new ArrayList<>();
                String userlink = Json.getSubElementAsText(authenticator, "_links.users.href");
                ApiClient.JsonCollection users = api.new JsonCollection(userlink);
                while (users.hasNext()) {
                    ObjectNode user = users.next();
                    if (request.operation != Operation.add) {
                        injectActions(user);
                    }
                    userlist.add(user);
                }
                authenticator.putArray(USERSTOKEN).addAll(userlist);
            }
        }
        return list;
    }

    private List<ObjectNode> listConnections(Request request) throws Exception {
        List<ObjectNode> list;
        if (request.resourceFilter != null) {
            String filter = request.resourceFilter.replace(NAMETOKEN, "alias");
            list = api.getConnections(filter);
            if (list.isEmpty()) {
                throw new NotFoundException("filter \""+filter+"\" returned no connections");
            }
        } else {
            ObjectNode single = api.getConnection(request.resource);
            if (single == null) {
                throw new NotFoundException("connection "+request.resource+" not found");
            }
            list = Arrays.asList(single);
        }
        if (request.operation != Operation.add) {
            for (ObjectNode connection : list) {
                injectActions(connection);
            }
        }
        return list;
    }

    /**
     * Returns a list of actions as indicated by the request in Official form.
     * @param request the request
     * @return found actions in Official form
     * @throws Exception
     */
    private List<ObjectNode> listActions(Request request) throws Exception {
        List<String> clauses = new ArrayList<>();
        if (request.action != null) {
            clauses.add("alias eq \""+request.action+"\"");
        }
        if (!Strings.isNullOrEmpty(request.actionFilter)) {
            clauses.add("("+request.actionFilter+")");
        }
        if (request.resource != null) {
            switch (request.resourceClass) {
            case user:
                clauses.add("authenticator.user.username eq \""+request.resource+"\"");
                String authenticator = Json.asText(request.entry.get("authenticator"));
                if (authenticator != null) {
                    clauses.add("authenticator.alias eq \""+authenticator+"\"");
                }
                break;
            case authenticator:
                clauses.add("authenticator.alias eq \""+request.resource+"\"");
                break;
            case connection:
                clauses.add("connection.alias eq \""+request.resource+"\"");
                break;
            default:
            }
        }
        String filter = clauses.stream().collect(Collectors.joining(" and "));
        return api.getActions(filter);
    }

	/*------------------------------------------------------------------------*
	 * Main File Processor                                                    *
	 *------------------------------------------------------------------------*/

    /**
     * Adds a "result" object containing "status" (success or error) and
     * "message" fields to the top of an ObjectNode
     * @param node the node to modify
     * @param success {@code true} for "success" else "error"
     * @param message the (optional) "message" to add
     * @param request the (optional) request from which to copy csvdata
     * @return the modified node
     */
    public static ObjectNode insertResult(ObjectNode node, boolean success, String message, Request request) {
        ObjectNode result = Json.setSubElement((ObjectNode)node.get("result"), "status", success ? "success" : "error");
        Json.setSubElement(result, "message", message);
        if (request != null && request.csvdata != null) {
            result.set("csvdata", request.csvdata);
        }
        return (ObjectNode) ((ObjectNode)Json.mapper.createObjectNode().set("result", result)).setAll(node);
    }

    /**
     * Adds a "result" object containing "status" (success or error) and
     * "message" fields to the top of an ObjectNode
     * @param node the node to modify
     * @param success {@code true} for "success" else "error"
     * @param message the (optional) "message" to add
     * @return the modified node
     */
    public static ObjectNode insertResult(ObjectNode node, boolean success, String message) {
        return insertResult(node, success, message, null);
    }

    private static class StackTraceCapture extends PrintStream {
        private boolean first = true;
        private ArrayNode trace;
        public StackTraceCapture(ArrayNode trace) {
            super (ByteStreams.nullOutputStream());
            this.trace = trace;
        }
        public void print(String s) {
            if (first) {
                first = false;
            } else {
                trace.add(s.replaceAll("^\\t*", ""));
            }
        }
    }

    public static ObjectNode insertResult(ObjectNode node, boolean success, Exception e) {
        ObjectNode update = insertResult(node, success, e.getClass().getSimpleName()+": "+e.getMessage());
        if (!(e instanceof ProcessingException)) {
            ArrayNode trace = Json.mapper.createArrayNode();
            e.printStackTrace(new StackTraceCapture(trace));
            ObjectNode result = (ObjectNode)update.get("result");
            result.set("trace", trace);
        }
        return update;
    }

    private ObjectNode addedPassword(String authenticator, ObjectNode entry, String password) {
        //   alias: authenticator
        //   username: username
        //   email: email
        //   password: encrypted password
        String encrypted = OpenSSLCrypt.encrypt(exportPassword, password);
        ObjectNode result = Json.mapper.createObjectNode();
        result.put("authenticator", authenticator);
        result.put("username", entry.get("username").asText());
        result.put("email", entry.get("email").asText());
        result.put("password", encrypted);
        return result;
    }

    private List<ObjectNode> passwordReport(ArrayNode passwords) {
        // create an object like:
        //   result:
        //     status: success
        //     message: added passwords
        //     passwords:
        //     - alias: authenticator
        //       username: username
        //       email: email
        //       password: encrypted password
        if (outputFormat != OutputFormat.csv && passwords.size() > 0) {
            ObjectNode report = insertResult(Json.mapper.createObjectNode(), true, "added passwords");
            Json.setSubElement(report, "result.passwords", passwords);
            return Collections.singletonList(report);
        } else {
            return Collections.emptyList();
        }
    }

	/*- add processors -------------------------------------------------------*/

    private void processAddUser(Request request, List<ObjectNode> results, ArrayNode passwords) throws Exception {
        // get or create the authenticator identified by "authenticator"
        String alias = Json.getSubElementAsText(request.entry, "authenticator");
        if (alias == null) {
            throw new ProcessingException("\"authenticator\" required when adding a user");
        }
        ObjectNode authenticator = getAuthenticatorByName(alias);
        if (authenticator == null) {
            throw new ProcessingException("authenticator \""+alias+"\" not found");
            /*
            authenticator = createAuthenticatorFromTemplate(alias);
            results.add(insertResult(authenticatorOfficial2Batch(authenticator),
                    true,
                    String.format("created authenticator %s with default template", alias)),
                    request);
            */
        }
        // Create user
        ObjectNode officialRequest = userBatch2Official(request.entry);
        String pwdhash = Json.getSubElementAsText(request.entry, "pwdhash");
        String password = null;
        if (pwdhash != null || generatePasswords) {
            password = PasswordGenerator.generatePassword();
            Json.setSubElement(officialRequest, "accept.password", password);
        } else {
            password = Json.getSubElementAsText(officialRequest, "accept.password");
        }
        ObjectNode officialResult = api.createUser(officialRequest, authenticator);
        if (officialResult == null) {
            throw new ProcessingException("user not created");
        }
        if (pwdhash != null) {
            versalex.set(alias, request.resource, "Pwdhash", pwdhash);
            officialResult.put("pwdhash", pwdhash);
            password = null;
        }
        if (password != null) {
            Json.setSubElement(officialResult, "accept.password", password);
            passwords.add(addedPassword(alias, request.entry, password));
        }
        if (request.actions != null) {
            createActions(request.actions, officialResult);
        }
        results.add(insertResult(userOfficial2Batch(officialResult), true, "created "+request.resource, request));
    }

    private void processAddAuthenticator(Request request, List<ObjectNode> results) throws Exception {
        ObjectNode officialRequest = authenticatorBatch2Official(request.entry);
        ObjectNode officialResult = api.createAuthenticator(officialRequest);
        if (officialResult == null) {
            throw new ProcessingException("error: authenticator not created");
        }
        if (request.actions != null) {
            createActions(request.actions, officialResult);
        }
        results.add(insertResult(authenticatorOfficial2Batch(officialResult), true, "created "+request.resource, request));
    }

    private void processAddConnection(Request request, List<ObjectNode> results) throws Exception {
        ObjectNode officialRequest = connectionBatch2Official(request.entry);
        // decrypt the password if it's encrypted
        String password = OpenSSLCrypt.decrypt(exportPassword,
                Json.getSubElementAsText(request.entry, "connect.password"));
        if (password != null) {
            Json.setSubElement(officialRequest, "connect.password", password);
        }
        ObjectNode officialResult = api.createConnection(officialRequest);
        if (officialResult == null) {
            throw new ProcessingException("error: connection not created");
        }
        ObjectNode batchResult = connectionOfficial2Batch(officialResult);
        if (password != null) {
            // protect the password in the result output, even if it was clearText in the request
            Json.setSubElement(batchResult, "connect.password", OpenSSLCrypt.encrypt(exportPassword, password));
        }
        api.deleteActions(officialResult);
        if (request.actions != null) {
            createActions(request.actions, officialResult);
        }
        results.add(insertResult(batchResult, true, "created "+request.resource, request));
    }

    private void processAddAction(Request request, List<ObjectNode> results) throws Exception {
        if (request.actionFilter != null) {
            throw new ProcessingException("actionFilter not allowed for add");
        }
        String alias = request.action;
        request.action = null;
        List<ObjectNode> tempResults = new ArrayList<>();
        List<ObjectNode> parents = processList(request, tempResults);
        if (parents.size() == 0) {
            throw new ProcessingException("parent object not found while adding action");
        } else if (parents.size() > 1) {
            throw new ProcessingException("ambiguous parent object while adding action");
        }
        ObjectNode parent = parents.get(0);
        ObjectNode actions = Json.mapper.createObjectNode();
        actions.set(alias, request.entry);
        actions = createActions(actions, parent);
        results.add(insertResult(actionOfficial2Batch((ObjectNode)actions.get(alias)), true, "created "+alias, request));
    }

    private void processAdd(Request request, List<ObjectNode> results, ArrayNode passwords) throws Exception {
        if (request.action != null || request.actionFilter != null) {
            processAddAction(request, results);
            return;
        } else if (request.resourceClass == ResourceClass.user) {
            processAddUser(request, results, passwords);
        } else if (request.resourceClass == ResourceClass.authenticator) {
            processAddAuthenticator(request, results);
        } else if (request.resourceClass == ResourceClass.connection) {
            processAddConnection(request, results);
        } else {
            throw new ProcessingException("unrecognized request");
        }
    }

	/*- update processors ----------------------------------------------------*/

    /**
     * Process an update request, appending to {@code results}.
     * <p/>
     * A typical {@code request.entry} contains some fields that are used as
     * a query and the rest used for the update. Usually this is fine, unless
     * you want to rename the entry (or move a user to a new authenticator,
     * since usernames are hierarchically organized under authenticators).
     * <p/>
     * For renames, {@code request.entry} may contain an {@code "update"} field,
     * whose values are overlaid on {@code request.entry} before updates are
     * applied.
     * <p/>
     * Note also that special delete/add logic is required to "rename" the
     * authenticator.
     * @param request the request, possibly with an {@code "update"} field
     * @param results where results are appended
     * @throws Exception
     */
    private void processUpdate(Request request, List<ObjectNode> results) throws Exception {
        // let update hold the update request (request.entry will be the query)
        ObjectNode update = request.entry.deepCopy();
        ObjectNode renames = (ObjectNode)update.remove("update");
        boolean movingUsers = request.resourceClass == ResourceClass.user &&
                renames != null &&
                renames.has("authenticator");
        if (renames != null) {
            Json.mergeUpdates(update, renames);
        }

        // run the query
        List<ObjectNode> tempResults = new ArrayList<>();
        List<ObjectNode> toUpdate;
        toUpdate = processList(request, tempResults);

        // apply the update to the query results
        for (int i=0; i<toUpdate.size(); i++) {
            try {
                ObjectNode updated;
                if (movingUsers) {
                    updated = moveUser(toUpdate.get(i), update);
                } else {
                    updated = updateResource(toUpdate.get(i), update);
                    createActions(request.actions, updated);
                }
                results.add(tempResults.get(i));
                String message = String.format("%s %s updated",
                    request.resourceClass.name(), getObjectName(updated));
                if (toUpdate.size() > 1) {
                    message += String.format(" (%d of %d)", i+1, toUpdate.size());
                }
                results.add(insertResult(official2Batch(updated), true, message, request));
            } catch (Exception e) {
                results.add(insertResult(official2Batch(toUpdate.get(i)), false, e));
            }
        }
    }

	/*- list processors ------------------------------------------------------*/

    private List<ObjectNode> processListUser(Request request, List<ObjectNode> results) throws Exception {
        List<ObjectNode> list;
        list = listUsers(request);
        int i = 1;
        for (ObjectNode user : list) {
            String message = String.format("%s user %s",
                    request.operation.tag(),
                    Json.getSubElementAsText(user, "username"));
            if (list.size() > 1) {
                message = String.format("%s (%d of %d)", message, i++, list.size());
            }
            results.add(insertResult(userOfficial2Batch(user), true, message, request));
        }
        return list;
    }

    private List<ObjectNode> processListAuthenticator(Request request, List<ObjectNode> results, boolean includeUsers) throws Exception {
        List<ObjectNode> list = listAuthenticators(request, includeUsers);
        int i = 1;
        for (ObjectNode authenticator : list) {
            ArrayNode users = (ArrayNode)authenticator.remove(USERSTOKEN);
            if (users == null) {
                users = Json.mapper.createArrayNode();
            }
            String message = String.format("%s authenticator %s",
                        request.operation.tag(),
                        Json.getSubElementAsText(authenticator, "alias"));
            if (list.size() > 1) {
                message = String.format("%s (%d of %d)", message, i++, list.size());
            }
            String messageHeader = message; // save for user messages
            if (includeUsers) {
                message = String.format("%s with %d users", message, users.size());
            }
            ObjectNode authenticatorResult = insertResult(authenticator, true, message, request);
            ArrayNode userResults = authenticatorResult.putArray(USERSTOKEN);
            for (int j=0; j<users.size(); j++) {
                message = String.format("%s: user %d of %d",
                        messageHeader, j+1, users.size());
                userResults.add(insertResult(userOfficial2Batch((ObjectNode)users.get(j)), true, message, request));
            }
            results.add(authenticatorOfficial2Batch(authenticatorResult));
        }
        return list;
    }

    private List<ObjectNode> processListConnection(Request request, List<ObjectNode> results) throws Exception {
        List<ObjectNode> list = listConnections(request);
        int i = 1;
        for (ObjectNode connection : list) {
            String message = String.format("%s connection %s",
                    request.operation.tag(),
                    Json.getSubElementAsText(connection, "alias"));
            if (list.size() > 1) {
                message = String.format("%s (%d of %d)", message, i++, list.size());
            }
            results.add(insertResult(connectionOfficial2Batch(connection), true, message, request));
        }
        return list;
    }

    /**
     * Process a request to list actions, returning the actions found in Official form
     * and adding actions in Batch form with an inserted result into {@code results}.
     * @param request the request to process
     * @param results ArrayNode into which Batch form results are added
     * @return List of Official form actions
     * @throws Exception
     */
    private List<ObjectNode> processListActions(Request request, List<ObjectNode> results) throws Exception {
        List<ObjectNode> list;
        list = listActions(request);

        int i = 1;
        for (ObjectNode action : list) {
            String message = String.format("%s action %s",
                    request.operation.tag(),
                    Json.getSubElementAsText(action, "alias"));
            if (list.size() > 1) {
                message = String.format("%s (%d of %d)", message, i++, list.size());
            }
            results.add(insertResult(actionOfficial2Batch(action), true, message, request));
        }
        return list;
    }

    /**
     * Process a request to list anything, returning the objects found in Official form
     * and adding objects in Batch form with an inserted result into {@code results}.
     * @param request the request to process
     * @param results ArrayNode into which Batch form results are added
     * @return List of Official form actions
     * @throws Exception
     */
    private List<ObjectNode> processList(Request request, List<ObjectNode> results) throws Exception {
        if (request.action != null || request.actionFilter != null) {
            return processListActions(request, results);
        }
        switch (request.resourceClass) {
        case user:
            return processListUser(request, results);
        case authenticator:
            return processListAuthenticator(request, results,
                    request.operation.equals(Operation.list) || request.operation.equals(Operation.delete));
        case connection:
            return processListConnection(request, results);
        case any:
        {
            List<ObjectNode> list = new ArrayList<>();
            try {
                list.addAll(processListUser(request, results));
            } catch (NotFoundException ignore) {}
            try {
                list.addAll(processListAuthenticator(request, results,
                            request.operation.equals(Operation.list) || request.operation.equals(Operation.delete)));
            } catch (NotFoundException ignore) {}
            try {
                list.addAll(processListConnection(request, results));
            } catch (NotFoundException ignore) {}
            if (list.isEmpty()) {
                throw new NotFoundException("filter \""+request.resourceFilter.replace(NAMETOKEN, "name")+"\" returned no users");
            }
            return list;
        }
        default:
            throw new ProcessingException("unrecognized request");
        }
    }

    private void appendAndFlattenUsers(JsonNode tempResult, List<ObjectNode> results) {
        results.add((ObjectNode)tempResult);
        ArrayNode users = (ArrayNode)((ObjectNode)tempResult).remove(USERSTOKEN);
        if (users != null && !users.isEmpty()) {
            users.forEach(u -> results.add((ObjectNode)u));
        }
    }

    private void appendAndFlattenUsers(List<ObjectNode> tempResults, List<ObjectNode> results) {
        if (tempResults != null && !tempResults.isEmpty()) {
            tempResults.forEach(tempResult -> appendAndFlattenUsers(tempResult, results));
        }
    }

	/*- request analyzer -----------------------------------------------------*/

    public static class Request {
        public ResourceClass resourceClass = null;  // indicates API endpoint: authenticator, user, connection
        public String resourceType = null;          // specific resource type, or generic type (same as class)
        public String resource = null;              // the resource name (username or alias in the API)
        public String resourceFilter = null;        // filter if using one
        public String action = null;                // action name
        public String actionFilter = null;          // filter for actions, if using one
        public ObjectNode entry = null;             // edited/cleaned up entry to query on
        public ObjectNode actions = null;           // actions separated out from entry and cleaned up
        public ObjectNode csvdata = null;           // original CSV data if the request was templated from CSV
        public Operation operation = null;          // the operation
    }

    private static final String NAMETOKEN = "$$name$$";

    public Request analyzeRequest(ObjectNode original) throws Exception {
        Request request = new Request();

        // make a copy of the original to edit and process in the request
        request.entry = (ObjectNode)(original.deepCopy());

        // figure out the requested operation
        request.operation = defaultOperation == Operation.preview
                ? Operation.preview
                : request.entry.has("operation")
                    ? Operation.valueOf(Json.asText(request.entry.remove("operation")))
                    : defaultOperation;
        boolean existing = request.operation != Operation.add;

        // set up the api client to use for this request (unless preview)
        if (request.operation != Operation.preview) {
            setApi(Json.asText(request.entry.remove("profile")));
        }
        
        // collect actions into an ObjectNode
        request.actions = normalizeActions(request.entry.remove("actions"));

        // squirrel away csvdata
        request.csvdata = (ObjectNode)request.entry.remove("csvdata");

        // remove other stuff that might be from a reflected result
        request.entry.remove(Arrays.asList("result", "id", "ready", "notReadyReason"));

        // see if there is a filter (and remove it from the entry)
        request.resourceFilter = Json.asText(request.entry.remove("filter"));
        if (request.resourceFilter != null && !existing) {
            throw new ProcessingException("\"filter\" valid only for list, update and delete");
        }

        // look for the resource name under username/authenticator/connection
        // if found we will know resourceClass and resource (name)
        for (ResourceClass r : EnumSet.allOf(ResourceClass.class)) {
            String resource = Json.getSubElementAsText(request.entry, r.tag());
            if (resource != null) {
                if (request.resourceClass==ResourceClass.user && r==ResourceClass.authenticator) {
                    // well, that's ok then and resourceClass should stay user
                    // note: this logic depends on user < authenticator in ResourceClass's "natural order"
                } else if (request.resourceClass != null) {
                    throw new ProcessingException("\""+r.tag()+"\" incompatible with \""+request.resourceClass.tag()+"\"");
                } else {
                    request.resource = resource;
                    request.resourceClass = r;
                }
            }
        }
        if (existing && request.resource == null && request.resourceFilter == null) {
            // if there is no name, then a blank filter is implied
            request.resourceFilter = "";
        }
        if (!existing && (request.resource == null || request.resourceClass==ResourceClass.any)) {
            // if it's an add and there is no name (or it's the generic "name"), that's an error
            throw new ProcessingException("\"username\", \"authenticator\" or \"connection\" required");
        }

        // resourceType: if there is no name, try resolving based on type
        // note that blank/missing type works only for "user"
        request.resourceType = Json.getSubElementAsText(request.entry, "type",
                request.resourceClass == null ? null : request.resourceClass.name());

        if (request.resourceType == null) {
            // this means there was no name and no type, so it's an "any" by default
            request.resourceClass = ResourceClass.any;
            request.resourceType = ResourceClass.any.name();
        } else if (request.resourceType.equals(ResourceClass.user.name())) {
            // the only user type is "user", so this forces class to user as well
            if (request.resourceClass == null || request.resourceClass == ResourceClass.any) {
                request.resourceClass = ResourceClass.user;
            } else if (request.resourceClass != ResourceClass.user) {
                throw new ProcessingException("\"type\":\""+request.resourceType+"\" incompatible with \""+request.resourceClass.tag()+"\"");
            }
        } else if (AUTH_TYPES.contains(request.resourceType)) {
            // any of the auth types force class to authenticator
            if (request.resourceClass == null || request.resourceClass == ResourceClass.any) {
                request.resourceClass = ResourceClass.authenticator;
            } else if (request.resourceClass != ResourceClass.authenticator) {
                throw new ProcessingException("\"type\":\""+request.resourceType+"\" incompatible with \""+request.resourceClass.tag()+"\"");
            }
            if (!existing && request.resourceType.equals("authenticator")) {
                throw new ProcessingException("generic type \"authenticator\" not valid for add: use specific type");
            }
        } else if (ACTION_TYPES.contains(request.resourceType)) {
            // then the type belongs to the action, so null it out for the parent resource
            request.resourceType = null;
        } else if (!request.resourceType.equals(ResourceClass.any.name())) {
            // any other type we'll assume to be a connection (except "any")
            if (request.resourceClass == null || request.resourceClass == ResourceClass.any) {
                request.resourceClass = ResourceClass.connection;
            } else if (request.resourceClass != ResourceClass.connection) {
                throw new ProcessingException("\"type\":\""+request.resourceType+"\" incompatible with \""+request.resourceClass.tag()+"\"");
            }
            if (!existing && request.resourceType.equals("connection")) {
                throw new ProcessingException("generic type \"connection\" not valid for add: use specific type");
            }
        }

        // if there specific resource type or name for an existing resource, update filter
        if (existing && request.resourceType != null &&
                (!request.resourceType.equals(request.resourceClass.name()) ||
                 request.resourceFilter != null && request.resource != null)) {
            List<String> clauses = new ArrayList<>();
            if (!Strings.isNullOrEmpty(request.resourceFilter)) {
                clauses.add("("+request.resourceFilter+")");
            }
            if (!request.resourceType.equals(request.resourceClass.name())) {
                clauses.add("type eq \""+request.resourceType+"\"");
            }
            // if there is a specific resource name requested, add it to the filter as well
            if (request.resource != null) {
                // this won't include users, so "alias" is always correct
                clauses.add(NAMETOKEN+" eq \""+request.resource+"\"");
                request.resource = null;
            }
            if (!clauses.isEmpty()) {
                request.resourceFilter = clauses.stream().collect(Collectors.joining(" and "));
            }
        }

        // remove the fake "user" type
        if (request.resourceClass == ResourceClass.user) {
            request.entry.remove("type");
        }

        // parse out action and actionFilter
        request.action = Json.getSubElementAsText(request.entry, "action");
        request.actionFilter = Json.asText(request.entry.remove("actionfilter")); // "" means "all"
        if (request.action==null && request.actionFilter==null && request.operation == Operation.run) {
            request.actionFilter = ""; // if you say run then an "all" actionfilter is implied
        }

        return request;
    }

	/*- main file processor --------------------------------------------------*/

    private void loadTemplate(TemplateExpander expander) throws Exception {
        // check for an explicit template
        if (!Strings.isNullOrEmpty(template)) {
            expander.template(template);
            return;
        }

        // check for empty file
        List<Map<String,String>> lines = expander.data();
        if (lines.size() == 0) {
            throw new ProcessingException("could not parse data from file");
        }

        // authenticator files have a "UserAlias" header
        if (lines.get(0).containsKey("UserAlias")) {
            expander.template(TemplateExpander.class.getResource("default/authenticator.yaml"));
            return;
        }

        // now check for "type": no "type" means a user file
        String type = lines.get(0).get("type");
        if (type == null) {
            expander.template(TemplateExpander.class.getResource("default/user.yaml"));
            return;
        }

        // toss out files that don't have consistent "type"
        for (Map<String,String> line : lines) {
            if (!type.equals(line.get("type"))) {
                throw new ProcessingException("file rows must be of the same type ("+type+")");
            }
        }

        // load the template resource
        switch (type) {
        case "as2":
        case "sftp":
        case "ftp":
            expander.template(TemplateExpander.class.getResource("default/"+type+".yaml"));
            return;
        default:
            throw new ProcessingException("no default template for type "+type);
        }
    }

    public boolean exists(String type, String name, String profile) {
        boolean found = false;
        try {
            if (!Strings.isNullOrEmpty(type) && !Strings.isNullOrEmpty(name)) {
                setApi(profile);
                switch (type) {
                case "user":
                    String[] path = name.split("\\\\", 2);
                    if (path.length == 1) {
                        found = api.getUser(name) != null;
                    } else {
                        String authfilter = "alias eq \""+path[0]+"\"";
                        found = api.getUser(authfilter, path[1]) != null;
                    }
                    break;
                case "authenticator":
                    found = getAuthenticatorByName(name) != null;
                    break;
                case "connection":
                    found = api.getConnection(name) != null;
                    break;
                default:
                }
            }
        } catch (Exception e) {
            // assume no
        }
        return found;
    }

    private TemplateExpander setupTemplateExpander() throws Exception {
        TemplateExpander expander = new TemplateExpander();
        expander.put("processor", this);
        expander.eval("generatePassword", "function generatePassword() { return "+PasswordGenerator.class.getName()+".generatePassword(); }");
        expander.eval("exists", "function exists(type,name,profile) { return processor.exists(type,name,profile||\"\"); }");
        return expander;
    }

    private Expander prepareContent(String content) throws Exception {
        // load file content into a string
        TemplateExpander expander = setupTemplateExpander();

        // Option 1: try to load it as a JSON or YAML file (unless --template)
        if (Strings.isNullOrEmpty(template)) {
            try {
                JsonNode json = Json.mapper.readTree(content);
                if (json.isContainerNode()) {
                    // treat a Json input as "template" processing one empty line of input
                    expander.template(json);
                    expander.line(Collections.emptyMap());
                }
            } catch (Exception notjson) {
                // try something else
            }
        }

        // Option 2: see if it can be loaded as CSV
        if (expander.data()==null) {
            // Json didn't load (or not attempted due to --template)
            try {
                expander.loadCsv(content);
                loadTemplate(expander);
                csvInput = true;
            } catch (Exception e) {
                throw new ProcessingException(e.getMessage());
            }
        }

        // ok, something worked -- return an iterator over the file
        return expander.expand();
    }

    /**
     * The main file processor. Load the file into {@code content} before calling.
     * The {@code fn} is included only for error reporting.
     * @param fn the name of the file to use for error reporting
     * @param content the content of the file loaded into a String
     * @param out where to write the output
     */
    public void processFile(String fn, String content, PrintStream out) throws IOException {
        ResultWriter writer = getWriter(out);
        Expander file = TemplateExpander.emptyExpander();
        ArrayNode passwords = Json.mapper.createArrayNode();
        try {
            file = prepareContent(content);
        } catch (ProcessingException e) {
            writer.write(insertResult(Json.setSubElement(null, "result.file", fn), false, e.getMessage()));
            return;
        } catch (Exception e) {
            writer.write(insertResult(Json.setSubElement(null, "result.file", fn), false, e));
        }

        for (TemplateExpander.ExpanderResult expanded : file) {
            if (!expanded.success()) {
                ObjectNode errorNode = Json.mapper.createObjectNode();
                ObjectNode resultNode = errorNode.putObject("result");
                if (csvInput) {
                    ObjectNode csvNode = resultNode.putObject("csv");
                    csvNode.put("error", expanded.exception().getMessage());
                    csvNode.put("line", expanded.lineNumber());
                    csvNode.set("data", Json.mapper.valueToTree(expanded.line()));
                }
                writer.write(errorNode);
            } else {
                List<JsonNode> requests = new ArrayList<>();
                if (expanded.expanded().isArray()) {
                    expanded.expanded().forEach(requests::add);
                } else {
                    requests.add(expanded.expanded());
                }
                if (csvInput) {
                    JsonNode line = Json.mapper.valueToTree(expanded.line());
                    requests.forEach(request -> ((ObjectNode)request).set("csvdata", line));
                }
                for (JsonNode request : requests) {
                    for (ObjectNode processed : processRequest(request, passwords)) {
                        writer.write(processed);
                    }
                }
            }
        }

        for (ObjectNode result : passwordReport(passwords)) {
            writer.write(result);
        }
        writer.close();
    }

    private List<ObjectNode> processRequest(JsonNode requestNode, ArrayNode passwords) {
        List<ObjectNode> results = new ArrayList<>();
        // pull the next element and make sure it's an object
        if (!requestNode.isObject()) {
            results.add(insertResult(Json.setSubElement(null, "result.request", requestNode.toString()), false, "invalid request"));
        } else {
            // process the request
            ObjectNode original = (ObjectNode)requestNode;
            try {
                Request request = analyzeRequest(original);
                if (traceRequests) {
                    System.err.println("REQUEST:");
                    System.err.println(Json.mapper.valueToTree(request).toPrettyString());
                }
                if (request.entry.isEmpty() &&
                        (request.operation==Operation.add || request.operation==Operation.update)) {
                    results.add(Json.setSubElement(original, "result.message", "empty request"));
                } else {
                    switch (request.operation) {
                    case preview:
                        results.add(insertResult(original, true, "request preview", request));
                        break;
                    case add:
                        processAdd(request, results, passwords);
                        break;
                    case list:
                        {
                            List<ObjectNode> tempResults = new ArrayList<>();
                            processList(request, tempResults);
                            appendAndFlattenUsers(tempResults, results);
                        }
                        break;
                    case update:
                        processUpdate(request, results);
                        break;
                    case delete:
                        {
                            List<ObjectNode> tempResults = new ArrayList<>();
                            List<ObjectNode> toDelete = processList(request, tempResults);
                            for (int i=0; i<toDelete.size(); i++) {
                                try {
                                    api.delete(toDelete.get(i));
                                    appendAndFlattenUsers(tempResults.get(i), results);
                                } catch (Exception e) {
                                    results.add(insertResult(toDelete.get(i), false, e));
                                }
                            }
                        }
                        break;
                    case run:
                        {
                            List<ObjectNode> tempResults = new ArrayList<>();
                            List<ObjectNode> toRun = processListActions(request, tempResults);
                            for (int i=0; i<toRun.size(); i++) {
                                try {
                                    ObjectNode action = toRun.get(i);
                                    ObjectNode output = api.runAction(action, request.entry);
                                    String message = "ran action "+Json.getSubElementAsText(action, "alias");
                                    if (toRun.size() > 1) {
                                        message += String.format(" (%d of %d)", i+1, toRun.size());
                                    }
                                    ObjectNode report = Json.mapper.createObjectNode();
                                    report.set("output", output);
                                    results.add(insertResult(report, true, message, request));
                                } catch (Exception e) {
                                    results.add(insertResult(toRun.get(i), false, e));
                                }
                            }
                        }
                        break;
                    default:
                        throw new ProcessingException("operation "+request.operation+" not supported");
                    }
                }
            } catch (Exception e) {
                results.add(insertResult(original, false, e));
            }
        }
        return results;
    }

    public interface ResultWriter {
        public void write(ObjectNode result) throws IOException;
        public void close() throws IOException;
    }

    private static class YamlWriter implements ResultWriter {
        private SequenceWriter seq;
        public YamlWriter(PrintStream out) throws IOException {
            this.seq = Json.mapper.writer().writeValuesAsArray(out);
        }
        @Override
        public void write(ObjectNode result) throws IOException {
            seq.write(result);
        };
        @Override
        public void close() throws IOException {
            seq.close();
        }
    }

    private static class JsonWriter implements ResultWriter {
        private PrintStream out;
        private SequenceWriter seq;
        public JsonWriter(PrintStream out) throws IOException {
            this.out = out;
            this.seq = new ObjectMapper()
                .configure(SerializationFeature.INDENT_OUTPUT, true)
                .configure(Feature.AUTO_CLOSE_TARGET, false)
                .writer()
                .writeValuesAsArray(out);
        }
        @Override
        public void write(ObjectNode result) throws IOException {
            seq.write(result);
        };
        @Override
        public void close() throws IOException {
            seq.close();
            out.println();
        }
    }

    private ResultWriter getWriter(PrintStream out) throws IOException {
        switch (outputFormat) {
        case yaml:
            return new YamlWriter(out);
        case json:
            return new JsonWriter(out);
        case csv:
            YamlWriter log = null;
            if (logOutput != null) {
                log = new YamlWriter(new PrintStream(logOutput.toFile()));
            }
            return new CsvExpander()
                    .template(outputTemplate)
                    .getWriter(out, log);
        default:
            return null; // can't happen
        }
    }

    /**
     * Convenience method for command line use: loads each filename and processes
     * it, producing output on the standard output. The {@code -} filename is
     * understood to mean "read the standard input"&mdash;use {@code ./-}
     * to process a file named {@code -}.
     * @param fns the filenames to process
     * @param out where to write the output
     * @throws IOException
     */
    public void processFiles(String[] fns, PrintStream out) throws IOException {
        for (String fn : fns) {
            String content = fn.equals("-")
                    ? new String(ByteStreams.toByteArray(System.in))
                    : new String(Files.readAllBytes(Paths.get(fn)));
            processFile(fn, content, out);
        }
    }

    /**
     * Fluent style constructor. Pass the REST api connection to use and
     * use set methods to set options.
     * @param api the REST api connection to use
     */
    public BatchProcessor(ApiClientFactory factory) {
        this.factory = factory;
        this.exportPassword = null;
        this.defaultOperation = Operation.add;
        this.template = null;
        this.traceRequests = false;
        this.generatePasswords = false;
        this.outputFormat = OutputFormat.yaml;
        this.logOutput = null;
        this.csvInput = false;
        loadVersaLex();
    }
}
