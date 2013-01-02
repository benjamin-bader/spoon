package com.squareup.spoon;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.logging.Logger;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.squareup.spoon.ExecutionTarget.FILE_RESULT;
import static com.squareup.spoon.ExecutionTarget.OUTPUT_FILE;
import static java.util.logging.Level.SEVERE;

/** Represents a collection of devices and the test configuration to be executed. */
public final class ExecutionSuite {
  public static final String DEFAULT_TITLE = "Spoon Execution";
  private static final Logger LOG = Logger.getLogger(ExecutionSuite.class.getSimpleName());

  private final String title;
  private final File sdkPath;
  private final File apk;
  private final File testApk;
  private final File output;
  private final boolean debug;
  private final Collection<String> serials;
  private final String classpath;

  private ExecutionSuite(String title, File sdkPath, File apk, File testApk, File output,
      boolean debug, Set<String> serials, String classpath) {
    this.title = title;
    this.sdkPath = sdkPath;
    this.apk = apk;
    this.testApk = testApk;
    this.output = output;
    this.debug = debug;
    this.serials = serials;
    this.classpath = classpath;
  }

  /** Returns {@code true} if there were no test failures or exceptions thrown. */
  public boolean run() {
    int targetCount = serials.size();
    if (targetCount == 0) {
      LOG.info("No devices.");
      return true;
    }

    LOG.info("Executing instrumentation on " + targetCount + " devices.");

    try {
      FileUtils.deleteDirectory(output);
    } catch (IOException e) {
      throw new RuntimeException("Unable to clean output directory: " + output, e);
    }

    final CountDownLatch done = new CountDownLatch(targetCount);
    final ExecutionSummary.Builder summaryBuilder = new ExecutionSummary.Builder()
        .setTitle(title)
        .setOutputDirectory(output)
        .start();

    try {
      for (final String serial : serials) {
        new Thread(new Runnable() {
          @Override public void run() {
            // Create an empty result just in case the execution fails before target.call() returns.
            ExecutionResult result = new ExecutionResult(serial);
            try {
              ExecutionTarget target =
                  new ExecutionTarget(sdkPath, apk, testApk, output, serial, debug, classpath);
              result = target.call();
              summaryBuilder.addResult(result);
            } catch (FileNotFoundException e) {
              // No results file means fatal exception before it could be written.
              String outputFolder = FilenameUtils.concat(output.getName(), serial);
              if (e.getMessage().contains(FilenameUtils.concat(outputFolder, FILE_RESULT))) {
                LOG.severe(String.format(
                    "Fatal exception while running on %s, please check %s for exception.", serial,
                    FilenameUtils.concat(outputFolder, OUTPUT_FILE)));
              } else {
                LOG.log(SEVERE, e.toString(), e);
              }
            } catch (Exception e) {
              LOG.log(SEVERE, e.toString(), e);
              result.setRuntimeException(e);
            } finally {
              done.countDown();
            }
          }
        }).start();
      }

      done.await();
    } catch (Exception e) {
      summaryBuilder.setException(e);
    }

    ExecutionSummary summary = summaryBuilder.end();

    // Write output files.
    summary.writeHtml();

    return summary.getException() == null && summary.getTotalFailure() == 0;
  }

  /** Build a test suite for the specified devices and configuration. */
  public static class Builder {
    private String title = DEFAULT_TITLE;
    private File sdk;
    private File apk;
    private File testApk;
    private File output;
    private boolean debug = false;
    private Set<String> serials;
    private String classpath = System.getProperty("java.class.path");

    /** Identifying title for this execution. */
    public Builder setTitle(String title) {
      this.title = title;
      return this;
    }

    /** Path to the local Android SDK directory. */
    public Builder setSdk(File sdk) {
      this.sdk = sdk;
      return this;
    }

    /** Path to application APK. */
    public Builder setApplicationApk(File apk) {
      this.apk = apk;
      return this;
    }

    /** Path to instrumentation APK. */
    public Builder setInstrumentationApk(File apk) {
      this.testApk = apk;
      return this;
    }

    /** Path to output directory. */
    public Builder setOutputDirectory(File dir) {
      this.output = dir;
      return this;
    }

    /** Whether or not debug logging is enabled. */
    public Builder setDebug(boolean debug) {
      this.debug = debug;
      return this;
    }

    /** Add a device serial for test execution. */
    public Builder addDevice(String serial) {
      if (this.serials == null) {
        this.serials = new HashSet<String>();
      }
      this.serials.add(serial);
      return this;
    }

    /** Add all currently attached device serials for test execution. */
    public Builder addAllAttachedDevices() {
      if (this.serials != null) {
        throw new IllegalStateException("Serial list already contains entries.");
      }
      if (this.sdk == null) {
        throw new IllegalStateException("SDK must be set before calling this method.");
      }
      this.serials = Utils.findAllDevices(sdk);
      return this;
    }

    /** Classpath to use for new JVM processes. */
    public Builder setClasspath(String classpath) {
      this.classpath = classpath;
      return this;
    }

    public ExecutionSuite build() {
      checkNotNull(sdk, "SDK is required.");
      checkNotNull(apk, "Application APK is required.");
      checkNotNull(testApk, "Instrumentation APK is required.");
      checkNotNull(output, "Output path is required.");
      checkNotNull(serials, "Device serials are required.");

      return new ExecutionSuite(title, sdk, apk, testApk, output, debug, serials, classpath);
    }
  }
}
