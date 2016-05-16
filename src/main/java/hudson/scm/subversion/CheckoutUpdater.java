/*
 * The MIT License
 *
 * Copyright (c) 2004-2010, Sun Microsystems, Inc., Kohsuke Kawaguchi, Fulvio Cavarretta,
 * Jean-Baptiste Quenot, Luca Domenico Milanesio, Renaud Bruyeron, Stephen Connolly,
 * Tom Huybrechts, Yahoo! Inc., Manufacture Francaise des Pneumatiques Michelin,
 * Romain Seguy
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package hudson.scm.subversion;

import hudson.Extension;
import hudson.Util;
import hudson.EnvVars;
import hudson.scm.SubversionSCM.External;
import hudson.scm.SubversionWorkspaceSelector;
import hudson.util.IOException2;
import hudson.util.StreamCopyThread;
import hudson.model.*;
import hudson.remoting.Channel;
import hudson.remoting.VirtualChannel;

import org.apache.commons.lang.time.FastDateFormat;
import org.kohsuke.stapler.DataBoundConstructor;
import org.tmatesoft.svn.core.SVNCancelException;
import org.tmatesoft.svn.core.SVNDepth;
import org.tmatesoft.svn.core.SVNException;
import org.tmatesoft.svn.core.internal.wc17.db.ISVNWCDb;
import org.tmatesoft.svn.core.internal.wc2.compat.SvnCodec;
import org.tmatesoft.svn.core.wc.SVNRevision;
import org.tmatesoft.svn.core.wc.SVNUpdateClient;
import org.tmatesoft.svn.core.wc2.SvnCheckout;
import org.tmatesoft.svn.core.wc2.SvnTarget;

import java.io.File;
import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import jenkins.model.Jenkins;

/**
 * {@link WorkspaceUpdater} that does a fresh check out.
 *
 * @author Kohsuke Kawaguchi
 */
public class CheckoutUpdater extends WorkspaceUpdater {
    private static final long serialVersionUID = -3502075714024708011L;

    private static final FastDateFormat fmt = FastDateFormat.getInstance("''yyyy-MM-dd'T'HH:mm:ss.SSS Z''");
    
    @DataBoundConstructor
    public CheckoutUpdater() {}

    @Override
    public UpdateTask createTask() {
        return new UpdateTask() {
            private static final long serialVersionUID = 8349986526712487762L;

            @Override
            public List<External> perform() throws IOException, InterruptedException {
                final SVNUpdateClient svnuc = clientManager.getUpdateClient();
                final List<External> externals = new ArrayList<External>(); // store discovered externals to here

                listener.getLogger().println("Cleaning local Directory " + location.getLocalDir());
                Util.deleteContentsRecursive(new File(ws, location.getLocalDir()));

                // buffer the output by a separate thread so that the update operation
                // won't be blocked by the remoting of the data
                PipedOutputStream pos = new PipedOutputStream();
                StreamCopyThread sct = new StreamCopyThread("svn log copier", new PipedInputStream(pos), listener.getLogger());
                sct.start();

                SVNRevision r = null;
                Jenkins instance = Jenkins.getInstance();
                List<AbstractProject> allItems;
                if (instance == null) {
                    allItems = Collections.emptyList();
                } else {
                    allItems = instance.getAllItems(AbstractProject.class);
                }
                for (AbstractProject<?, ?> job : allItems) {
                    if(job.isBuilding() && job.isParameterized()) {
                        VirtualChannel channel = null;
                        //channel = job.getCharacteristicEnvVars().getWorkspace().getChannel();
                        //channel.
                        //String nodeName = ((Channel) channel).getName();
                        // EnvVars env = job.getEnvironment(instance.getNode(nodeName), listener);
                        EnvVars env = job.getCharacteristicEnvVars();
                        for(String key : env.descendingKeySet()) {
                            listener.getLogger().println(key);
                        }
                        for (JobProperty property : job.getProperties().values()) {
                            if (property instanceof ParametersDefinitionProperty) {
                                ParametersDefinitionProperty pdp = (ParametersDefinitionProperty) property;
                                for (String propertyName : pdp.getParameterDefinitionNames()) {
                                    listener.getLogger().println(propertyName);
                                    if (propertyName.contains("SVN_PEG_PARAMETER")) {
                                        listener.getLogger().println("YES!");
                                        ParameterDefinition pd = pdp.getParameterDefinition(propertyName);
                                        ParameterValue pv = pd.getDefaultParameterValue();
                                        String replacement = pd.getDescriptor().getValuePage();
                                        listener.getLogger().println(replacement);
                                        if (pv != null) {
                                            //replacement = pv.getValue().toString();
                                            //replacement = String.valueOf(pv.createVariableResolver(null).resolve(propertyName));
                                            listener.getLogger().println("SVN_PEG_PARAMETER:" + replacement);
                                            if(!replacement.isEmpty()) {
                                                r = SVNRevision.parse(replacement);
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                if (r == null) {
                    r = getRevision(location);
                }

                try {
                    String revisionName = String.valueOf(r.getNumber());

                    listener.getLogger().println("Checking out " + location.getSVNURL().toString() + " at revision " +
                            revisionName);

                    File local = new File(ws, location.getLocalDir());
                    SubversionUpdateEventHandler eventHandler = new SubversionUpdateEventHandler(new PrintStream(pos), externals, local, location.getLocalDir());
                    svnuc.setEventHandler(eventHandler);
                    svnuc.setExternalsHandler(eventHandler);
                    svnuc.setIgnoreExternals(location.isIgnoreExternalsOption());
                    SVNDepth svnDepth = getSvnDepth(location.getDepthOption());
                    SvnCheckout checkout = svnuc.getOperationsFactory().createCheckout();
                    checkout.setSource(SvnTarget.fromURL(location.getSVNURL(), SVNRevision.HEAD));
                    checkout.setSingleTarget(SvnTarget.fromFile(local.getCanonicalFile()));
                    checkout.setDepth(svnDepth);
                    checkout.setRevision(r);
                    checkout.setAllowUnversionedObstructions(true);
                    checkout.setIgnoreExternals(location.isIgnoreExternalsOption());
                    checkout.setExternalsHandler(SvnCodec.externalsHandler(svnuc.getExternalsHandler()));

                    // Statement to guard against JENKINS-26458.
                    if (SubversionWorkspaceSelector.workspaceFormat == SubversionWorkspaceSelector.OLD_WC_FORMAT_17) {
                        SubversionWorkspaceSelector.workspaceFormat = ISVNWCDb.WC_FORMAT_17;
                    }

                    // Workaround for SVNKIT-430 is to set the working copy format when
                    // a checkout is performed.
                    checkout.setTargetWorkingCopyFormat(SubversionWorkspaceSelector.workspaceFormat);
                    checkout.run();
                } catch (SVNCancelException e) {
                    if (isAuthenticationFailedError(e)) {
                        e.printStackTrace(listener.error("Failed to check out " + location.remote));
                        return null;
                    } else {
                        listener.error("Subversion checkout has been canceled");
                        throw (InterruptedException)new InterruptedException().initCause(e);
                    }
                } catch (SVNException e) {
                    e.printStackTrace(listener.error("Failed to check out " + location.remote));
                    throw new IOException("Failed to check out " + location.remote, e) ;
                } finally {
                    try {
                        pos.close();
                    } finally {
                        try {
                            sct.join(); // wait for all data to be piped.
                        } catch (InterruptedException e) {
                            throw new IOException2("interrupted", e);
                        }
                    }
                }

                return externals;
            }
        };
    }

    @Extension
    public static class DescriptorImpl extends WorkspaceUpdaterDescriptor {
        @Override
        public String getDisplayName() {
            return Messages.CheckoutUpdater_DisplayName();
        }
    }
}
