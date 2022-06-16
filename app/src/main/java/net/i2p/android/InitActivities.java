package net.i2p.android;

import android.content.Context;
import android.content.res.Resources;
import android.os.Build;

import net.i2p.android.router.R;
import net.i2p.android.router.util.Util;
import net.i2p.data.DataHelper;
import net.i2p.util.FileUtil;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

//  Wouldn't this be better as a private class in MainActivity?

class InitActivities {

    private final Context ctx;
    private final String myDir;
    private final String _ourVersion;

    private static final String CONFIG_FILE = "android.config";
    private static final String PROP_NEW_INSTALL = "i2p.newInstall";
    private static final String PROP_NEW_VERSION = "i2p.newVersion";
    private static final String PROP_INSTALLED_VERSION = "i2p.version";

    public InitActivities(Context c) {
        ctx = c;
        myDir = Util.getFileDir(c);
        Util.i("My app directory is "+myDir);
        _ourVersion = Util.getOurVersion(c);
    }

    void debugStuff() {
        Util.d("java.io.tmpdir" + ": " + System.getProperty("java.io.tmpdir"));
        Util.d("java.vendor" + ": " + System.getProperty("java.vendor"));
        Util.d("java.version" + ": " + System.getProperty("java.version"));
        Util.d("os.arch" + ": " + System.getProperty("os.arch"));
        Util.d("os.name" + ": " + System.getProperty("os.name"));
        Util.d("os.version" + ": " + System.getProperty("os.version"));
        Util.d("user.dir" + ": " + System.getProperty("user.dir"));
        Util.d("user.home" + ": " + System.getProperty("user.home"));
        Util.d("user.name" + ": " + System.getProperty("user.name"));
        Util.d("getFilesDir()" + ": " + myDir);
        Util.d("max mem" + ": " + DataHelper.formatSize(Runtime.getRuntime().maxMemory()));
        Util.d("Package" + ": " + ctx.getPackageName());
        Util.d("Version" + ": " + _ourVersion);
        Util.d("MODEL" + ": " + Build.MODEL);
        Util.d("DISPLAY" + ": " + Build.DISPLAY);
        Util.d("VERSION" + ": " + Build.VERSION.RELEASE);
        Util.d("SDK" + ": " + Build.VERSION.SDK_INT);
    }

    void initialize() {
        Util.i("Initializing the I2P resources");

        List<Properties> lProps = Util.getPropertiesFromPreferences(ctx);
        Properties props = lProps.get(0);

        props.setProperty("i2p.dir.temp", myDir + "/tmp");
        props.setProperty("i2p.dir.pid", myDir + "/tmp");
        // Time disabled in default router.config
        // But lots of time problems on Android, not all carriers support NITZ
        // and there was no NTP before 3.0. Tablets should be fine?
        // Phones in airplane mode with wifi enabled still a problem.
        // Deactivated phones in airplane mode definitely won't have correct time.
        if (Build.VERSION.SDK_INT < 11)  // Honeycomb 3.0
            props.setProperty("time.disabled", "false");
        mergeResourceToFile(R.raw.router_config, "router.config", props);
        mergeResourceToFile(R.raw.logger_config, "logger.config", lProps.get(1));
        // This is not needed for now, i2ptunnel.config only contains tunnel
        // settings, which can now be configured manually. We don't want to
        // overwrite the user's tunnels.
        //mergeResourceToFile(R.raw.i2ptunnel_config, "i2ptunnel.config", null);
        copyResourceToFileIfAbsent(R.raw.i2ptunnel_config, "i2ptunnel.config");
        // FIXME this is a memory hog to merge this way
        mergeResourceToFile(R.raw.hosts_txt, "hosts.txt", null);
        mergeResourceToFile(R.raw.more_hosts_txt, "hosts.txt", null);
        copyResourceToFile(R.raw.blocklist_txt, "blocklist.txt");

        File abDir = new File(myDir, "addressbook");
        abDir.mkdir();
        copyResourceToFile(R.raw.subscriptions_txt, "addressbook/subscriptions.txt");
        mergeResourceToFile(R.raw.addressbook_config_txt, "addressbook/config.txt", null);

        File docsDir = new File(myDir, "docs");
        docsDir.mkdir();
        /*copyResourceToFile(R.raw.ahelper_conflict_header_ht, "docs/ahelper-conflict-header.ht");
        copyResourceToFile(R.raw.ahelper_new_header_ht, "docs/ahelper-new-header.ht");
        copyResourceToFile(R.raw.ahelper_notfound_header_ht, "docs/ahelper-notfound-header.ht");
        copyResourceToFile(R.raw.auth_header_ht, "docs/auth-header.ht");
        copyResourceToFile(R.raw.baduri_header_ht, "docs/baduri-header.ht");
        copyResourceToFile(R.raw.denied_header_ht, "docs/denied-header.ht");
        copyResourceToFile(R.raw.dnf_header_ht, "docs/dnf-header.ht");
        copyResourceToFile(R.raw.dnfb_header_ht, "docs/dnfb-header.ht");
        copyResourceToFile(R.raw.dnfh_header_ht, "docs/dnfh-header.ht");
        copyResourceToFile(R.raw.dnfp_header_ht, "docs/dnfp-header.ht");
        copyResourceToFile(R.raw.enc_header_ht, "docs/enc-header.ht");
        copyResourceToFile(R.raw.encp_header_ht, "docs/encp-header.ht");
        copyResourceToFile(R.raw.localhost_header_ht, "docs/localhost-header.ht");
        copyResourceToFile(R.raw.nols_header_ht, "docs/nols-header.ht");
        copyResourceToFile(R.raw.nolsp_header_ht, "docs/nolsp-header.ht");
        copyResourceToFile(R.raw.noproxy_header_ht, "docs/noproxy-header.ht");
        copyResourceToFile(R.raw.protocol_header_ht, "docs/protocol-header.ht");
        copyResourceToFile(R.raw.reset_header_ht, "docs/reset-header.ht");
        copyResourceToFile(R.raw.resetp_header_ht, "docs/resetp-header.ht");*/

        File cssDir = new File(docsDir, "themes/console/light");
        cssDir.mkdirs();
        //copyResourceToFile(R.raw.console_css, "docs/themes/console/light/console.css");
        //copyResourceToFile(R.raw.android_css, "docs/themes/console/light/android.css");

        File imgDir = new File(docsDir, "themes/console/images");
        imgDir.mkdir();
        copyResourceToFile(R.drawable.i2plogo, "docs/themes/console/images/i2plogo.png");
        copyResourceToFile(R.drawable.itoopie_sm, "docs/themes/console/images/itoopie_sm.png");
        //copyResourceToFile(R.drawable.outbound, "docs/themes/console/images/outbound.png");
        //copyResourceToFile(R.drawable.inbound, "docs/themes/console/images/inbound.png");

        File img2Dir = new File(cssDir, "images");
        img2Dir.mkdir();
        //copyResourceToFile(R.drawable.header, "docs/themes/console/light/images/header.png");

        File certDir = new File(myDir, "certificates");
        certDir.mkdir();
        File certificates = new File(myDir, "certificates");
        File[] allCertificates = certificates.listFiles();
        if ( allCertificates != null) {
            for (File f : allCertificates) {
                Util.d("Deleting old certificate file/dir " + f);
                FileUtil.rmdir(f, false);
            }
        }
        File netDBDir = new File(myDir, "netDB");
        netDBDir.mkdir();
        //unzipResourceToDir(R.raw.netdb_zip, "netDB");
        unzipResourceToDir(R.raw.certificates_zip, "certificates");
        File familyKeysInvalid = new File(myDir,"certificates/family");
        FileUtil.rmdir(familyKeysInvalid, false);

        // Set up the locations so settings can find them
        System.setProperty("i2p.dir.base", myDir);
        System.setProperty("i2p.dir.config", myDir);
        System.setProperty("wrapper.logfile", myDir + "/wrapper.log");
    }

    /**
     *  @param f relative to base dir
     */
    private void copyResourceToFileIfAbsent(int resID, String f) {
        File file = new File(myDir, f);
        if (!file.exists())
            copyResourceToFile(resID, f);
    }

    /**
     *  @param f relative to base dir
     */
    private void copyResourceToFile(int resID, String f) {
        InputStream in = null;
        FileOutputStream out = null;

        Util.d("Creating file " + f + " from resource");
        byte buf[] = new byte[4096];
        try {
            // Context methods
            in = ctx.getResources().openRawResource(resID);
            out = new FileOutputStream(new File(myDir, f));

            int read;
            while ( (read = in.read(buf)) != -1)
                out.write(buf, 0, read);

        } catch (IOException ioe) {
            Util.e("copyResourceToFile" + "IOE: ", ioe);
        } catch (Resources.NotFoundException nfe) {
            Util.e("copyResourceToFile" + "NFE: ", nfe);
        } finally {
            if (in != null) try { in.close(); } catch (IOException ioe) {
                Util.e("copyResourceToFile" + "IOE in.close(): ", ioe);
            }
            if (out != null) try { out.close(); } catch (IOException ioe) {
                Util.e("copyResourceToFile" + "IOE out.close(): ", ioe);
            }
        }
    }
    /**
     *  @param folder relative to base dir
     */
    private void unzipResourceToDir(int resID, String folder) {
        InputStream in = null;
        FileOutputStream out = null;
        ZipInputStream zis = null;

        Util.i("Creating files in '" + myDir + "/" + folder + "/' from resource");
        try {
            // Context methods
            in = ctx.getResources().openRawResource(resID);
            zis = new ZipInputStream((in));
            ZipEntry ze;
            while ((ze = zis.getNextEntry()) != null) {
                out = null;
                Util.i("unzipping "+ze);
                try {
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    byte[] buffer = new byte[1024];
                    int count;
                    while ((count = zis.read(buffer)) != -1) {
                        baos.write(buffer, 0, count);
                    }
                    String name = ze.getName();
                    File f = new File(myDir + "/" + folder +"/" + name);
                    String canonicalPath = f.getCanonicalPath().replace("/user/0/", "/data/");
                    // account for canonical path differences when using .aab bundles
                    if (!canonicalPath.startsWith(myDir.replace("/user/0/", "/data/"))) {
                        // If these don't match, there's a path-traversal possibility.
                        // So ignore it.
                        Util.e("Path mismatch bug " + canonicalPath.toString() + " " + myDir.toString());
                    } else if (ze.isDirectory()) {
                        Util.i("Creating directory " + myDir + "/" + folder +"/" + name + " from resource");
                        f.mkdir();
                    } else {
                        Util.i("Creating file " + myDir + "/" + folder +"/" + name + " from resource");
                        //create all the leading directories
                        File newFile = new File(myDir+"/"+folder+"/"+name);
                        newFile.getParentFile().mkdirs();
                        byte[] bytes = baos.toByteArray();
                        out = new FileOutputStream(f);
                        out.write(bytes);
                    }
                } catch (IOException ioe) {
                    Util.e("unzipResourceToDir" + "IOE: ", ioe);
                } finally {
                    if (out != null) {
                        try {
                            out.close();
                        } catch (IOException ioe) {
                            Util.e("unzipResourceToDir" + "IOE: interior out.close ", ioe);
                        }
                        out = null;
                    }
                }
            }
        } catch (IOException ioe) {
            Util.e("unzipResourceToDir" + "IOE: ", ioe);
        } catch (Resources.NotFoundException nfe) {
            Util.e("unzipResourceToDir" + "NFE: ", nfe);
        } finally {
            if (in != null) try { in.close(); } catch (IOException ioe) {
                Util.e("unzipResourceToDir" + "IOE: in.close() ", ioe);
            }
            if (out != null) try { out.close(); } catch (IOException ioe) {
                Util.e("unzipResourceToDir" + "IOE: out.close() ", ioe);
            }
            if (zis != null) try { zis.close(); } catch (IOException ioe) {
                Util.e("unzipResourceToDir" + "IOE: zis.close() ", ioe);
            }
        }
    }

    /**
     *  Load defaults from resource,
     *  then add props from settings,
     *  and write back.
     *
     *  @param f relative to base dir
     *  @param overrides local overrides or null
     */
    private void mergeResourceToFile(int resID, String f, Properties overrides) {
        Util.mergeResourceToFile(ctx, myDir, f, resID, overrides, null);
    }

    /**
     *  Check for new version.
     *  FIXME we could just use shared prefs for this instead of storing in a file
     *  @return true if new version
     */
    private boolean checkNewVersion() {
        Properties props = new Properties();

        Util.i("Checking for a new install/version");
        InputStream fin = null;
        try {
            fin = ctx.openFileInput(CONFIG_FILE);
            DataHelper.loadProps(props,  fin);
        } catch (IOException ioe) {
            Util.i("Looks like a new install");
        } finally {
            if (fin != null) {
                try {
                    Util.i("fin was not null "+CONFIG_FILE);
                    fin.close();
                } catch (IOException ioe) {
                    Util.i("Error loading config:", ioe);
                }
            }else {
                Util.i("fin was null");
            }
        }

        String oldVersion = props.getProperty(PROP_INSTALLED_VERSION);
        Util.i("Old version is:"+oldVersion);
        boolean newInstall = oldVersion == null;
        if (newInstall)
            return true;
        boolean newVersion = !_ourVersion.equals(oldVersion);

        if (newVersion) {
            Util.d("New version " + _ourVersion);
            props.setProperty(PROP_INSTALLED_VERSION, _ourVersion);
            try {
                DataHelper.storeProps(props, ctx.getFileStreamPath(CONFIG_FILE));
            } catch (IOException ioe) {
                Util.d("Failed to write " + CONFIG_FILE);
            }
        }
        return newVersion;
    }
}
