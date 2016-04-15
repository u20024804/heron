// Copyright 2016 Twitter. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.twitter.heron.scheduler.local;

import java.io.File;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.xml.bind.DatatypeConverter;

import org.apache.commons.io.FileUtils;

import com.twitter.heron.api.generated.TopologyAPI;
import com.twitter.heron.proto.system.ExecutionEnvironment;
import com.twitter.heron.spi.common.Config;
import com.twitter.heron.spi.common.PackingPlan;
import com.twitter.heron.spi.common.ShellUtils;
import com.twitter.heron.spi.scheduler.ILauncher;
import com.twitter.heron.spi.statemgr.SchedulerStateManagerAdaptor;
import com.twitter.heron.spi.utils.NetworkUtils;
import com.twitter.heron.spi.utils.Runtime;

/**
 * Launch topology locally to a working directory.
 */
public class LocalLauncher implements ILauncher {
  protected static final Logger LOG = Logger.getLogger(LocalLauncher.class.getName());

  private Config config;
  private Config runtime;

  private String topologyWorkingDirectory;
  private String coreReleasePackage;
  private String targetCoreReleaseFile;
  private String targetTopologyPackageFile;

  @Override
  public void initialize(Config config, Config runtime) {

    this.config = config;
    this.runtime = runtime;

    // get the topology working directory
    this.topologyWorkingDirectory = LocalContext.workingDirectory(config);

    // get the path of core release URI
    this.coreReleasePackage = LocalContext.corePackageUri(config);

    // form the target dest core release file name
    this.targetCoreReleaseFile = Paths.get(
        topologyWorkingDirectory, "heron-core.tar.gz").toString();

    // form the target topology package file name
    this.targetTopologyPackageFile = Paths.get(
        topologyWorkingDirectory, "topology.tar.gz").toString();
  }

  @Override
  public void close() {

  }

  /**
   * Encode the JVM options
   *
   * @return encoded string
   */
  protected String formatJavaOpts(String javaOpts) {
    String javaOptsBase64 = DatatypeConverter.printBase64Binary(
        javaOpts.getBytes(Charset.forName("UTF-8")));

    return String.format("\"%s\"", javaOptsBase64.replace("=", "&equals;"));
  }

  /**
   * Actions to execute before launch such as check whether the
   * topology is already running
   *
   * @return true, if successful
   */
  @Override
  public boolean prepareLaunch(PackingPlan packing) {
    return true;
  }

  /**
   * Launch the topology
   */
  @Override
  public boolean launch(PackingPlan packing) {
    LOG.info("Launching topology for local cluster " + LocalContext.cluster(config));

    TopologyAPI.Topology topology = Runtime.topology(runtime);

    // download the core and topology packages into the working directory
    if (!downloadAndExtractPackages()) {
      LOG.severe("Failed to download the core and topology packages");
      return false;
    }

    String schedulerClassPath = new StringBuilder()
        .append(LocalContext.schedulerSandboxClassPath(config)).append(":")
        .append(LocalContext.packingSandboxClassPath(config)).append(":")
        .append(LocalContext.stateManagerSandboxClassPath(config))
        .toString();

    String schedulerCmd = String.format("%s/%s %s %s %s %s %s %s %s %s %s",
        LocalContext.javaHome(config),
        "bin/java",
        "-cp",
        schedulerClassPath,
        "com.twitter.heron.scheduler.SchedulerMain",
        "--cluster " + LocalContext.cluster(config),
        "--role " + LocalContext.role(config),
        "--environment " + LocalContext.environ(config),
        "--topology_name " + topology.getName(),
        "--topology_jar " + LocalContext.topologyJarFile(config),
        "--http_port " + NetworkUtils.getFreePort()
    );

    LOG.log(Level.FINE, "Scheduler command line: {0}", schedulerCmd.toString());

    Process p = ShellUtils.runASyncProcess(true, schedulerCmd.toString(),
        new File(topologyWorkingDirectory));

    if (p == null) {
      LOG.severe("Failed to start SchedulerMain using: " + schedulerCmd);
      return false;
    }

    LOG.info(String.format(
        "For checking the status and logs of the topology, use the working directory %s",
        LocalContext.workingDirectory(config)));

    return true;
  }

  @Override
  public boolean postLaunch(PackingPlan packing) {
    return true;
  }

  /**
   * Download heron core and the topology packages into topology working directory
   *
   * @return true if successful
   */
  protected boolean downloadAndExtractPackages() {

    // log the state manager being used, for visibility and debugging purposes
    SchedulerStateManagerAdaptor stateManager = Runtime.schedulerStateManagerAdaptor(runtime);
    LOG.log(Level.FINE, "State manager used: {0} ", stateManager.getClass().getName());

    // if the working directory does not exist, create it.
    File workingDirectory = new File(topologyWorkingDirectory);
    if (!workingDirectory.exists()) {
      LOG.fine("The working directory does not exist; creating it.");
      if (!workingDirectory.mkdirs()) {
        LOG.severe("Failed to create directory: " + workingDirectory.getPath());
        return false;
      }
    }

    // copy the heron core release package to the working directory and untar it
    LOG.log(Level.FINE, "Fetching heron core release {0}", coreReleasePackage);
    LOG.fine("If release package is already in the working directory");
    LOG.fine("the old one will be overwritten");
    if (!copyPackage(coreReleasePackage, targetCoreReleaseFile)) {
      LOG.severe("Failed to fetch the heron core release package.");
      return false;
    }

    // untar the heron core release package in the working directory
    LOG.log(Level.FINE, "Untar the heron core release {0}", coreReleasePackage);
    if (!untarPackage(targetCoreReleaseFile, topologyWorkingDirectory)) {
      LOG.severe("Failed to untar heron core release package.");
      return false;
    }

    // remove the core release package
    if (!FileUtils.deleteQuietly(new File(targetCoreReleaseFile))) {
      LOG.warning("Unable to delete the core release file: " + targetCoreReleaseFile);
    }

    // give warning for overwriting existing topology package
    String topologyPackage = Runtime.topologyPackageUri(runtime).toString();
    LOG.log(Level.FINE, "Fetching topology package {0}", Runtime.topologyPackageUri(runtime));
    LOG.fine("If topology package is already in the working directory");
    LOG.fine("the old one will be overwritten");

    // fetch the topology package
    if (!copyPackage(topologyPackage, targetTopologyPackageFile)) {
      LOG.severe("Failed to fetch the heron core release package.");
      return false;
    }

    // untar the topology package
    LOG.log(Level.FINE, "Untar the topology package: {0}", topologyPackage);

    if (!untarPackage(targetTopologyPackageFile, topologyWorkingDirectory)) {
      LOG.severe("Failed to untar topology package.");
      return false;
    }

    // remove the topology package
    if (!FileUtils.deleteQuietly(new File(targetTopologyPackageFile))) {
      LOG.warning("Unable to delete the core release file: " + targetTopologyPackageFile);
    }

    return true;
  }

  /**
   * Copy a URL package to a target folder
   *
   * @param corePackageURI the URI to download core release package
   * @param targetFile the target filename to download the release package to
   * @return true if successful
   */
  protected boolean copyPackage(String corePackageURI, String targetFile) {

    // get the directory containing the target file
    Path filePath = Paths.get(targetFile);
    File parentDirectory = filePath.getParent().toFile();

    // using curl copy the url to the target file
    String cmd = String.format("curl %s -o %s", corePackageURI, targetFile);
    int ret = ShellUtils.runSyncProcess(false, true, cmd,
        new StringBuilder(), new StringBuilder(), parentDirectory);

    return ret == 0 ? true : false;
  }

  /**
   * Untar a tar package to a target folder
   *
   * @param packageName the tar package
   * @param targetFolder the target folder
   * @return true if untar successfully
   */
  protected boolean untarPackage(String packageName, String targetFolder) {
    String cmd = String.format("tar -xvf %s", packageName);

    int ret = ShellUtils.runSyncProcess(false, true, cmd,
        new StringBuilder(), new StringBuilder(), new File(targetFolder));

    return ret == 0 ? true : false;
  }
}