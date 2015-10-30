// Copyright (c) Microsoft. All rights reserved.
// Licensed under the MIT license. See License.txt in the project root.

package com.microsoft.alm.plugin.idea.utils;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intellij.ide.passwordSafe.PasswordSafe;
import com.intellij.ide.passwordSafe.PasswordSafeException;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.components.StoragePathMacros;
import com.microsoft.alm.common.utils.UrlHelper;
import com.microsoft.alm.plugin.authentication.AuthenticationInfo;
import com.microsoft.alm.plugin.context.ServerContext;
import com.microsoft.alm.plugin.context.ServerContext.Type;
import com.microsoft.alm.plugin.context.ServerContextManager;
import com.microsoft.alm.plugin.services.ServerContextStore;
import com.microsoft.alm.plugin.services.ServerContextStore.Key;
import com.microsoft.teamfoundation.core.webapi.model.TeamProjectCollectionReference;
import com.microsoft.teamfoundation.core.webapi.model.TeamProjectReference;
import com.microsoft.teamfoundation.sourcecontrol.webapi.model.GitRepository;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Stores a ServerContextItemStore to file and handles writing and reading the objects
 */
@State(
        name = "TfServers",
        storages = {@Storage(
                file = StoragePathMacros.APP_CONFIG + "/tf_settings.xml")}
)
public class ServerContextSettings implements PersistentStateComponent<ServerContextSettings.ServerContextItemsStore> {

    private static final Logger logger = LoggerFactory.getLogger(ServerContextSettings.class);

    /**
     * Lifecycle:
     * <ol>
     * <li>During plugin startup, IntelliJ calls the settings file from disk and calls loadState() which sets restoreState</li>
     * <li>Later ServerContextManager loads and takes ownership of this data and sets restoreState to <code>null</code></li>
     * <li>Finally, during plugin shutdown, IntelliJ calls getState() get the contents to write back out to disk</li>
     * </ol>
     * One other interesting note, is that ServerContextManager makes calls to save the passwords as it goes
     * (but doesn't update any other state data).  Ideally, the passwords would be saved at the same time everything
     * is saved (i.e. in getState()), but this is not possible, because by the time IntelliJ calls into here the
     * PasswordManager service has already been shut down.
     */
    private ServerContextItemsStore restoreState;

    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * IntelliJ calls this method to load from stored state during plugin initialization.
     * <p/>
     * Shortly thereafter, ServerContextManager will initialize and will take ownership of this data with actively
     * managed ServerContexts, and this state data should not be used again.
     *
     * @param state
     */
    public void loadState(final ServerContextItemsStore state) {
        this.restoreState = state;
    }

    /**
     * IntelliJ calls this method to save to stored state when it shuts down.
     */
    public ServerContextItemsStore getState() {
        return getServerContextsToPersist();
    }

    public static class ServerContextItemsStore {

        public ServerContextItemStore[] serverContextItemStores;

        public static class ServerContextItemStore {
            //fields have to be public, so IntelliJ can write them to the persistent store
            public Type type = null;
            public String uri = null;
            public String accountUUID = null;
            public String teamProjectCollectionReference = null;
            public String teamProjectReference = null;
            public String gitRepository = null;
        }
    }

    // This default instance is only returned in the case of tests or we are somehow running outside of IntelliJ
    private static ServerContextSettings DEFAULT_INSTANCE = new ServerContextSettings();

    public static ServerContextSettings getInstance() {
        if (ApplicationManager.getApplication() != null) {
            return ServiceManager.getService(ServerContextSettings.class);
        } else {
            return DEFAULT_INSTANCE;
        }
    }

    public void saveServerContextSecrets(final ServerContext context) {
        final Key key = ServerContextStore.Key.create(context);

        final AuthenticationInfo authenticationInfo = context.getAuthenticationInfo();
        final String stringValue = writeToJson(authenticationInfo);

        writePassword(key, stringValue);
    }

    private ServerContextItemsStore getServerContextsToPersist() {
        if (restoreState != null) {
            // ServerContextManager never loaded the data
            return restoreState;
        } else {
            final ServerContextItemsStore saveState = new ServerContextItemsStore();

            List<ServerContext> serverContexts = ServerContextManager.getInstance().getAllServerContexts();
            List<ServerContextItemsStore.ServerContextItemStore> stores = new ArrayList<ServerContextItemsStore.ServerContextItemStore>();
            for (ServerContext context : serverContexts) {
                ServerContextItemsStore.ServerContextItemStore store = new ServerContextItemsStore.ServerContextItemStore();
                store.type = context.getType();
                store.uri = context.getUri().toString();
                store.accountUUID = context.getAccountId() != null ? context.getAccountId().toString() : null;
                store.teamProjectCollectionReference = writeToJson(context.getTeamProjectCollectionReference());
                store.teamProjectReference = writeToJson(context.getTeamProjectReference());
                store.gitRepository = writeToJson(context.getGitRepository());
                stores.add(store);
            }
            saveState.serverContextItemStores = stores.toArray(new ServerContextItemsStore.ServerContextItemStore[stores.size()]);
            return saveState;
        }
    }

    public List<ServerContext> getServerContextsToRestore() {
        if (restoreState == null || restoreState.serverContextItemStores == null) {
            return Collections.EMPTY_LIST;
        }

        final List<ServerContext> serverContexts = new ArrayList<ServerContext>();
        for (final ServerContextItemsStore.ServerContextItemStore toRestore : restoreState.serverContextItemStores) {
            Key key = null;
            try {
                final URI serverUri = UrlHelper.getBaseUri(toRestore.uri);
                key = ServerContextStore.Key.create(serverUri);
                final AuthenticationInfo authenticationInfo = getServerContextSecrets(key);
                if (authenticationInfo != null) {
                    ServerContext context = null;
                    if (toRestore.type == Type.VSO) {
                        final UUID accountUuid = UUID.fromString(toRestore.accountUUID);
                        context = ServerContext.createVSOContext(serverUri, accountUuid, authenticationInfo);
                    } else if (toRestore.type == Type.TFS) {
                        context = ServerContext.createTFSContext(serverUri, authenticationInfo);
                    }
                    if (context != null) {
                        context.setTeamProjectCollectionReference(readFromJson(toRestore.teamProjectCollectionReference, TeamProjectCollectionReference.class));
                        context.setTeamProjectReference(readFromJson(toRestore.teamProjectReference, TeamProjectReference.class));
                        context.setGitRepository(readFromJson(toRestore.gitRepository, GitRepository.class));
                        serverContexts.add(context);
                    } else {
                        forgetServerContextSecrets(key);
                    }
                }
            } catch (final Throwable restoreThrowable) {
                logger.warn("Failed to restore server context", restoreThrowable);
                // attempt to clean up left over data
                if (key != null) {
                    try {
                        forgetServerContextSecrets(key);
                    } catch (final Throwable cleanupThrowable) {
                        logger.warn("Failed to cleanup invalid server context");
                    }
                }
            }
        }

        restoreState = null; // null this out since data ownership has now been passed
        return serverContexts;
    }

    public void forgetServerContextSecrets(final Key key) {
        forgetPassword(key);
    }

    public AuthenticationInfo getServerContextSecrets(final Key key) throws IOException {
        final String authInfoSerialized = readPassword(key);

        AuthenticationInfo info = null;
        if (StringUtils.isNotEmpty(authInfoSerialized)) {
            info = readFromJson(authInfoSerialized, AuthenticationInfo.class);
        }

        if (info == null) {
            forgetServerContextSecrets(key);
            logger.warn("getServerContextSecrets: info was null for key: ", key);
            return null;
        }
        return info;
    }

    private <T> String writeToJson(final T object) {
        String json = null;
        if (object != null) {
            try {
                json = mapper.writeValueAsString(object);
            } catch (JsonProcessingException e) {
                logger.warn("Failed to convert to string", e);
            }
        }
        return json;
    }

    private <T> T readFromJson(final String json, final Class<T> valueType) throws IOException {
        T object = null;
        if (json != null) {
            object = mapper.readValue(json, valueType);
        }
        return object;
    }

    private void forgetPassword(final Key key) {
        try {
            PasswordSafe.getInstance().removePassword(null, this.getClass(), key.stringValue());
        } catch (PasswordSafeException e) {
            logger.warn("Failed to clear password store", e);
        }
    }

    private void writePassword(final Key key, final String value) {
        try {
            PasswordSafe.getInstance().storePassword(null, this.getClass(), key.stringValue(), value);
        } catch (PasswordSafeException e) {
            logger.warn("Failed to get password", e);
        }
    }

    private String readPassword(final Key key) {
        String password = null;
        try {
            password = PasswordSafe.getInstance().getPassword(null, this.getClass(), key.stringValue());
        } catch (PasswordSafeException e) {
            logger.warn("Failed to read password", e);
        }
        return password;
    }

}
