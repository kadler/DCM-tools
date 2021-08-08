package com.github.ibmioss.dcmtools;

import java.beans.PropertyVetoException;
import java.io.IOException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

import javax.security.auth.x500.X500Principal;

import com.github.ibmioss.dcmtools.utils.CertUtils;
import com.github.ibmioss.dcmtools.utils.ConsoleUtils;
import com.github.ibmioss.dcmtools.utils.DcmApiCaller;
import com.github.ibmioss.dcmtools.utils.DcmChangeTracker;
import com.github.ibmioss.dcmtools.utils.KeyStoreInterrogator;
import com.github.ibmioss.dcmtools.utils.KeyStoreLoader;
import com.github.ibmioss.dcmtools.utils.StringUtils;
import com.github.ibmioss.dcmtools.utils.StringUtils.TerminalColor;
import com.github.ibmioss.dcmtools.utils.TempFileManager;
import com.ibm.as400.access.AS400SecurityException;
import com.ibm.as400.access.ErrorCompletingRequestException;
import com.ibm.as400.access.ObjectDoesNotExistException;

public class CertFileImporter {
    public static class ImportOptions extends DcmUserOpts {
        private boolean isPasswordProtected = false;
        private String password = null;
        private String label = null;
        private boolean isCasOnly = false;

        public String getPasswordOrNull() throws IOException {
            if (!isPasswordProtected) {
                return null;
            }
            if (StringUtils.isEmpty(password) && !isYesMode()) {
                final String resp = ConsoleUtils.askUserForPwd("Enter input file password: ");
                return password = resp;
            } else {
                return password;
            }
        }

        public boolean isPasswordProtected() {
            return isPasswordProtected;
        }

        public void setPassword(final String password) {
            this.password = password;
        }

        public void setPasswordProtected(final boolean isPasswordProtected) {
            this.isPasswordProtected = isPasswordProtected;
        }

        public void setLabel(final String _label) {
            this.label = _label;
        }

        public String getLabel() {
            return this.label;
        }

        public boolean isCasOnly() {
            return this.isCasOnly;
        }

        public void setCasOnly(boolean _casOnly) {
            this.isCasOnly = _casOnly;
        }
    }

    private final List<String> m_fileNames;

    public CertFileImporter(final List<String> _fileNames) throws IOException {
        m_fileNames = new LinkedList<String>();
        for(String fileName : _fileNames) {
            m_fileNames.add(null == fileName ? KeyStoreLoader.extractTrustFromInstalledCerts() : fileName);
        }
    }

    public void doImport(final ImportOptions _opts) throws KeyStoreException, IOException, NoSuchAlgorithmException, CertificateException, PropertyVetoException, AS400SecurityException, ErrorCompletingRequestException, InterruptedException, ObjectDoesNotExistException {

        final boolean isYesMode = _opts.isYesMode();
        // Initialize keystore from file of unknown type
        final KeyStore keyStore = new KeyStoreLoader(m_fileNames, _opts.getPasswordOrNull(), _opts.getLabel(), _opts.isCasOnly()).getKeyStore();
        System.out.println(StringUtils.colorizeForTerminal("Sanity check successful", TerminalColor.GREEN));

        final DcmChangeTracker dcmTracker = new DcmChangeTracker(_opts);
        final KeyStoreInterrogator dcmChecker = dcmTracker.getStartingSnapshot();

        // Check for conflicting aliases, where the certificate is already in the store under a different alias
        System.out.println("Checking if the certificate is already in DCM....");
        for (final String alias : Collections.list(keyStore.aliases())) {
            System.out.println("checking for conflicting cert to the one with alias "+alias);
            final Certificate cert = keyStore.getCertificate(alias);
            String conflictingAlias = dcmChecker.getAliasOfCertOrNull(cert);
            if(null != conflictingAlias && !conflictingAlias.equals(alias)) {
                System.out.println(StringUtils.colorizeForTerminal("WARNING: The following certificate already exists in the keystore with certificate id '"+conflictingAlias+"':\n"+CertUtils.getCertInfoStr(cert,"    "), TerminalColor.YELLOW));
                keyStore.deleteEntry(alias);
            }
        }
        //check for the case where the alias already exists and we might overwrite it on import
        System.out.println("Checking if the alias already exists....");
        for (final String alias : Collections.list(keyStore.aliases())) {
            System.out.println("checking cert at alias "+alias);
            final Certificate cert = keyStore.getCertificate(alias);
            Certificate preExistingCert = dcmChecker.getKeyStore().getCertificate(alias);
            if(null != preExistingCert) {
                System.out.println(StringUtils.colorizeForTerminal("WARNING: The following certificate will be replaced with certificate id '"+alias+"':\n"+CertUtils.getCertInfoStr(preExistingCert,"    "), TerminalColor.YELLOW));
                if(!isYesMode) {
                    final String reply = ConsoleUtils.askUserWithDefault("Do you want to continue anyway and replace the above certificates in DCM? [y/N] ", "N");
                    if (!reply.toLowerCase().trim().startsWith("y")) {
                        keyStore.deleteEntry(alias);
                        System.out.println("OK, not importing this certificate");
                    }
                }
            }
        }
        if(!keyStore.aliases().hasMoreElements()) {
            throw new IOException("No certificates to import");
        }
        // Ask user confirmation
        System.out.println("The following certificates will be processed:");
        for (final String alias : Collections.list(keyStore.aliases())) {
            final Certificate cert = keyStore.getCertificate(alias);
            if (cert instanceof X509Certificate) {
                System.out.println("    " + alias + ": " + StringUtils.colorizeForTerminal(((X509Certificate) cert).getIssuerX500Principal().getName(X500Principal.RFC1779), TerminalColor.CYAN));
            } else {
                System.out.println("    " + alias + ": " + StringUtils.colorizeForTerminal("<unknown CN>", TerminalColor.BRIGHT_RED));
            }
        }
        final String reply = isYesMode ? "y" : ConsoleUtils.askUserWithDefault("Do you want to import ALL of the above certificates into DCM? [y/N] ", "N");
        if (!reply.toLowerCase().trim().startsWith("y")) {
            throw new IOException("No certificates to import");
        }

        // Convert the KeyStore object to a file in the format needed by the DCM API
        final String dcmImportFile = new KeyStoreLoader(keyStore).saveToDcmApiFormatFile(TempFileManager.TEMP_KEYSTORE_PWD);
        ;

        // .... and... call the DCM API to do the import!
        try (DcmApiCaller caller = new DcmApiCaller(isYesMode)) {
            caller.callQykmImportKeyStore(_opts.getDcmStore(), _opts.getDcmPassword(), dcmImportFile, TempFileManager.TEMP_KEYSTORE_PWD);
        }
    }

}
