/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2010, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.jboss.modules;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.List;

/**
 * A remote filesystem-backed module loader.
 *
 * @author <a href="mailto:ales.justin@jboss.org">Ales Justin</a>
 */
public class RemoteModuleLoader extends ModuleLoader {

    private final static String INDEX = ".index";
    private final static String XML = "module.xml";

    private final ModuleLoader delegate;
    private final String rootURL;
    private final String appServerVersion;
    private final File repoRoot;

    public RemoteModuleLoader() {
        this(new LocalModuleLoader());
    }

    protected RemoteModuleLoader(ModuleLoader delegate) {
        if (delegate == null)
            throw new IllegalArgumentException("Null delegate");

        this.delegate = delegate;
        this.rootURL = System.getProperty("modules.remote.root.url", "http://www.jboss.org/jbossas/modules/");
        this.appServerVersion = System.getProperty("modules.remote.app.server.version", "trunk");
        String modulePath = System.getProperty("module.path", System.getenv("MODULEPATH"));
        this.repoRoot = new File(modulePath);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected Module preloadModule(final ModuleIdentifier identifier) throws ModuleLoadException {
        if (identifier.equals(ModuleIdentifier.SYSTEM)) {
            return preloadModule(ModuleIdentifier.SYSTEM, SystemClassPathModuleLoader.getInstance());
        }

        return super.preloadModule(identifier);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected ModuleSpec findModule(final ModuleIdentifier moduleIdentifier) throws ModuleLoadException {
        try {
            ModuleSpec spec = delegate.findModule(moduleIdentifier);
            if (spec != null)
                return spec;
        } catch (ModuleLoadException ignored) {
        }

        return fetchRemoteModule(moduleIdentifier) ? delegate.findModule(moduleIdentifier) : null;
    }

    protected boolean fetchRemoteModule(ModuleIdentifier moduleIdentifier) throws ModuleLoadException {
        try {
            String localPath = toPathString(moduleIdentifier, false);
            String remotePath = toPathString(moduleIdentifier, true);

            InputStream moduleXMLStream;
            File localModuleXML = getLocalFile(localPath + XML);
            boolean existsLocally = localModuleXML.exists();
            if (existsLocally)
                moduleXMLStream = new FileInputStream(localModuleXML);
            else
                moduleXMLStream = getInputStream(remotePath, XML);

            if (moduleXMLStream != null) {

                if (existsLocally == false) {
                    File moduleXML = writeStream(moduleXMLStream, localPath + XML);
                    moduleXMLStream = new FileInputStream(moduleXML);
                }

                List<String> resourcePaths = ModuleXmlParser.parseResourcePaths(moduleXMLStream, moduleIdentifier);
                for (String resource : resourcePaths) {
                    String fullName = localPath + resource;
                    if (getLocalFile(fullName).exists() == false) {
                        InputStream jarStream = getInputStream(remotePath, resource);
                        if (jarStream != null) {
                            writeStream(jarStream, fullName);
                        }
                    }
                    if (getLocalFile(fullName + INDEX).exists() == false) {
                        InputStream indexStream = getInputStream(remotePath, resource + INDEX);
                        if (indexStream != null) {
                            writeStream(indexStream, fullName + INDEX);
                        }
                    }
                }
                log("Module fetch completed OK: " + moduleIdentifier);
                return true;
            } else {
                return false;
            }
        } catch (IOException e) {
            e.printStackTrace();
            throw new ModuleLoadException("Cannot fetch remote resource: " + moduleIdentifier, e);
        }
    }

    protected File getLocalFile(String name) {
        return new File(repoRoot, name);
    }

    protected InputStream getInputStream(String path, String resource) {
        try {
            URL url = new URL(rootURL + path + resource);
            log("Fetching resource: " + url);
            return url.openStream();
        } catch (Exception e) {
            log("Cannot open stream: " + e);
            return null;
        }
    }

    protected File writeStream(InputStream inputStream, String name) throws IOException {
        try {
            File file = new File(repoRoot, name);
            File parent = file.getParentFile();
            if (parent.exists() == false)
                parent.mkdirs();

            log("Saving resource: " + file);
            FileOutputStream out = new FileOutputStream(file);
            try {
                int b;
                while ((b = inputStream.read()) != -1) {
                    out.write(b);
                }
                out.flush();
                log("Resource saved: " + file);
                return file;
            } finally {
                out.close();
            }
        } catch (Exception e) {
            e.printStackTrace();
            throw new IOException(e);
        } finally {
            inputStream.close();
        }
    }

    private String toPathString(ModuleIdentifier moduleIdentifier, boolean addVersion) {
        final StringBuilder builder = new StringBuilder();
        if (addVersion)
            builder.append(appServerVersion).append(File.separatorChar);
        builder.append(moduleIdentifier.getName().replace('.', File.separatorChar));
        builder.append(File.separatorChar).append(moduleIdentifier.getSlot());
        builder.append(File.separatorChar);
        return builder.toString();
    }

    public String toString() {
        final StringBuilder b = new StringBuilder();
        b.append("remote module loader @").append(Integer.toHexString(hashCode())).append(" (url: ").append(rootURL);
        b.append(')');
        return b.toString();
    }

    private void log(String msg) {
        System.out.println(msg);
    }
}
