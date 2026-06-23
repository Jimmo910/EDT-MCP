/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

import javax.crypto.spec.PBEKeySpec;

import org.eclipse.equinox.security.storage.provider.IPreferencesContainer;
import org.eclipse.equinox.security.storage.provider.PasswordProvider;

/**
 * Supplies a stable master password for the Eclipse default secure storage so that EDT's
 * {@code IInfobaseAccessManager.updateSettings} (used by {@code set_infobase_credentials} and
 * {@code create_infobase}) never raises the blocking <b>"Secure Storage — please enter a new master
 * password"</b> dialog on a fresh / headless stand (issue #194). On such a stand writing the infobase
 * connection credentials initializes the Eclipse keyring, which otherwise prompts for a master password
 * and hangs the unattended call.
 *
 * <p>Registered via the {@code org.eclipse.equinox.security.secureStorage} extension at a priority just
 * above the platform's interactive {@code DefaultPasswordProvider} (priority 2) — so on a headless stand
 * where the prompt is the only alternative this provider wins, while on a desktop the OS keyring providers
 * (Windows DPAPI / GNOME keyring), which sit at higher priorities, keep handling secrets.
 *
 * <p><b>Safe by construction.</b> Equinox stamps each encrypted value with the moduleID of the provider
 * that wrote it and decrypts via that same provider (by moduleID, NOT by priority), so this provider only
 * ever owns the values it itself wrote — a user's existing entries keep decrypting with their own provider
 * and cannot be corrupted by this one.
 */
public final class McpSecureStorageProvider extends PasswordProvider
{
    /**
     * Non-secret application salt mixed into the derived master password. A salt is a fixed,
     * publishable application constant (NOT a credential), so it carries no secret value on its own.
     */
    private static final String SALT = "com.ditrix.edt.mcp.server.secureStorage.v1"; //$NON-NLS-1$

    /** NUL field separator between the identity components of the hashed material. */
    private static final char SEP = (char)0;

    /**
     * Master password for the LOCAL Eclipse keyring — NOT an infobase user password. It is now
     * <b>derived per machine + user</b> (from the OS user name and home directory mixed with a fixed
     * application {@link #SALT}) rather than stored as a hardcoded secret, so no credential literal
     * remains in source. The derivation is deterministic, so the keyring re-opens every session
     * without a prompt; on a trusted-caller server the keyring only protects infobase connection
     * settings. Because the value is derived, a keyring created under a different OS user / home will
     * not decrypt — acceptable here, as it only caches re-settable infobase connection settings.
     */
    private static final String MASTER = deriveMaster();

    @Override
    public PBEKeySpec getPassword(IPreferencesContainer container, int passwordType)
    {
        // Always supply the stable derived password: the moduleID design (above) means we only ever own
        // the values we encrypt, so this cannot affect a user's existing secure-storage entries, and on a
        // desktop the higher-priority OS keyring providers are chosen ahead of us anyway.
        return new PBEKeySpec(MASTER.toCharArray());
    }

    /**
     * Derives the stable master password from machine/installation-local identity so that no secret
     * literal lives in source. Computes {@code Base64url(SHA-256(user.name SEP user.home SEP SALT))},
     * where {@code SEP} is a NUL separator.
     *
     * @return the derived, deterministic master password for the current OS user / home
     */
    private static String deriveMaster()
    {
        String material = System.getProperty("user.name", "") //$NON-NLS-1$ //$NON-NLS-2$
            + SEP + System.getProperty("user.home", "") //$NON-NLS-1$ //$NON-NLS-2$
            + SEP + SALT;
        try
        {
            MessageDigest digest = MessageDigest.getInstance("SHA-256"); //$NON-NLS-1$
            byte[] hash = digest.digest(material.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        }
        catch (NoSuchAlgorithmException e)
        {
            // SHA-256 is mandated by every JRE; absence is a broken platform, not a recoverable state.
            throw new IllegalStateException("SHA-256 algorithm is not available", e); //$NON-NLS-1$
        }
    }

    @Override
    public boolean retryOnError(Exception e, IPreferencesContainer container)
    {
        return false; // a wrong stable password must not loop
    }
}
