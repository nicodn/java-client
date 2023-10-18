/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.appium.java_client.service.local;

import com.google.common.collect.ImmutableList;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.appium.java_client.android.options.context.SupportsChromedriverExecutableOption;
import io.appium.java_client.android.options.signing.SupportsKeystoreOptions;
import io.appium.java_client.internal.ReflectionHelpers;
import io.appium.java_client.remote.MobileBrowserType;
import io.appium.java_client.remote.options.SupportsAppOption;
import io.appium.java_client.service.local.flags.GeneralServerFlag;
import io.appium.java_client.service.local.flags.ServerArgument;
import lombok.SneakyThrows;
import org.openqa.selenium.Capabilities;
import org.openqa.selenium.Platform;
import org.openqa.selenium.os.ExecutableFinder;
import org.openqa.selenium.remote.Browser;
import org.openqa.selenium.remote.service.DriverService;

import javax.annotation.Nullable;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Strings.isNullOrEmpty;
import static java.util.Objects.requireNonNull;
import static org.openqa.selenium.remote.CapabilityType.PLATFORM_NAME;

public final class AppiumServiceBuilder
        extends DriverService.Builder<AppiumDriverLocalService, AppiumServiceBuilder> {

    /**
     * The environmental variable used to define
     * the path to executable appium.js (1.4.x and lower) or
     * main.js (1.5.x and higher).
     */
    public static final String APPIUM_PATH = "APPIUM_BINARY_PATH";

    /**
     * The environmental variable used to define
     * the path to executable NodeJS file (node.exe for WIN and
     * node for Linux/MacOS X).
     */
    private static final String NODE_PATH = "NODE_BINARY_PATH";

    public static final String BROADCAST_IP4_ADDRESS = "0.0.0.0";
    public static final String BROADCAST_IP6_ADDRESS = "::";
    private static final Path APPIUM_PATH_SUFFIX = Paths.get("appium", "build", "lib", "main.js");
    public static final int DEFAULT_APPIUM_PORT = 4723;
    private final Map<String, String> serverArguments = new HashMap<>();
    private File appiumJS;
    private File node;
    private String ipAddress = BROADCAST_IP4_ADDRESS;
    private Capabilities capabilities;
    private boolean autoQuoteCapabilitiesOnWindows = false;
    private static final Function<File, String> APPIUM_JS_NOT_EXIST_ERROR = fullPath -> String.format(
            "The main Appium script does not exist at '%s'", fullPath.getAbsolutePath());
    private static final Function<File, String> NODE_JS_NOT_EXIST_ERROR = fullPath ->
            String.format("The main NodeJS executable does not exist at '%s'", fullPath.getAbsolutePath());

    private static final List<String> PATH_CAPABILITIES = List.of(
            SupportsChromedriverExecutableOption.CHROMEDRIVER_EXECUTABLE_OPTION,
            SupportsKeystoreOptions.KEYSTORE_PATH_OPTION,
            SupportsAppOption.APP_OPTION
    );

    public AppiumServiceBuilder() {
        usingPort(DEFAULT_APPIUM_PORT);
        withEnvironment(System.getenv());
    }

    /**
     * Provides a measure of how strongly this {@link DriverService} supports the given
     * {@code capabilities}. A score of 0 or less indicates that this {@link DriverService} does not
     * support instances of {@link org.openqa.selenium.WebDriver} that require {@code capabilities}.
     * Typically, the score is generated by summing the number of capabilities that the driver
     * service directly supports that are unique to the driver service (that is, things like
     * "{@code proxy}" don't tend to count to the score).
     * Higher the score, higher the possibility of getting grid sessions created sooner.
     */
    @Override
    public int score(Capabilities capabilities) {
        int score = 0;

        if (capabilities.getCapability(PLATFORM_NAME) != null) {
            score++;
        }

        String browserName = capabilities.getBrowserName();
        if (Browser.CHROME.is(browserName) || browserName.equalsIgnoreCase(MobileBrowserType.ANDROID)
                || Browser.SAFARI.is(browserName)) {
            score++;
        }

        return score;
    }

    private static File validatePath(@Nullable String fullPath, String errMsg) {
        if (fullPath == null) {
            throw new InvalidServerInstanceException(errMsg);
        }
        File result = new File(fullPath);
        if (!result.exists()) {
            throw new InvalidServerInstanceException(errMsg);
        }
        return result;
    }

    private static File findBinary(String name, String errMsg) {
        return validatePath(new ExecutableFinder().find(name), errMsg);
    }

    private static File findNpm() {
        return findBinary("npm",
                "Node Package Manager (npm) is either not installed or its executable is not present in PATH");
    }

    private static File findMainScript() {
        File npm = findNpm();
        List<String> cmdLine = System.getProperty("os.name").toLowerCase().contains("win")
                // npm is a batch script, so on windows we need to use cmd.exe in order to execute it
                ? Arrays.asList("cmd.exe", "/c", String.format("\"%s\" root -g", npm.getAbsolutePath()))
                : Arrays.asList(npm.getAbsolutePath(), "root", "-g");
        ProcessBuilder pb = new ProcessBuilder(cmdLine);
        String nodeModulesRoot;
        try {
            nodeModulesRoot = new String(pb.start().getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            throw new InvalidServerInstanceException(
                    "Cannot retrieve the path to the folder where NodeJS modules are located", e);
        }
        File mainAppiumJs = Paths.get(nodeModulesRoot, APPIUM_PATH_SUFFIX.toString()).toFile();
        if (!mainAppiumJs.exists()) {
            throw new InvalidServerInstanceException(APPIUM_JS_NOT_EXIST_ERROR.apply(mainAppiumJs));
        }
        return mainAppiumJs;
    }

    protected File findDefaultExecutable() {
        if (this.node != null) {
            validatePath(this.node.getAbsolutePath(), NODE_JS_NOT_EXIST_ERROR.apply(this.node));
            return this.node;
        }

        File node = loadPathFromEnv(NODE_PATH);
        if (node != null) {
            validatePath(node.getAbsolutePath(), NODE_JS_NOT_EXIST_ERROR.apply(node));
            this.node = node;
            return this.node;
        }

        this.node = findBinary("node",
                "NodeJS is either not installed or its executable not present in PATH");
        return this.node;
    }

    /**
     * Boolean arguments have a special moment:
     * the presence of an arguments means "true". This method
     * was designed for these cases.
     *
     * @param argument is an instance which contains the argument name.
     * @return the self-reference.
     */
    public AppiumServiceBuilder withArgument(ServerArgument argument) {
        serverArguments.put(argument.getArgument(), "");
        return this;
    }

    /**
     * Adds a server argument.
     *
     * @param argument is an instance which contains the argument name.
     * @param value    A non null string value. (Warn!!!) Boolean arguments have a special moment:
     *                 the presence of an arguments means "true". At this case an empty string
     *                 should be defined.
     * @return the self-reference.
     */
    public AppiumServiceBuilder withArgument(ServerArgument argument, String value) {
        String argName = argument.getArgument();
        switch (argName) {
            case "--port":
            case "-p":
                usingPort(Integer.parseInt(value));
                break;
            case "--address":
            case "-a":
                withIPAddress(value);
                break;
            case "--log":
            case "-g":
                withLogFile(new File(value));
                break;
            case "--base-path":
                serverArguments.put(argName, sanitizeBasePath(value));
                break;
            default:
                serverArguments.put(argName, value);
                break;
        }
        return this;
    }

    private static String sanitizeBasePath(String basePath) {
        basePath = requireNonNull(basePath).trim();
        checkArgument(
                !basePath.isEmpty(),
                "Given base path is not valid - blank or empty values are not allowed for base path"
        );
        return basePath.endsWith("/") ? basePath : basePath + "/";
    }

    /**
     * Adds capabilities.
     *
     * @param capabilities is an instance of {@link Capabilities}.
     * @return the self-reference.
     */
    public AppiumServiceBuilder withCapabilities(Capabilities capabilities) {
        this.capabilities = (this.capabilities == null ? capabilities : this.capabilities)
                .merge(capabilities);
        return this;
    }

    /**
     * Adds capabilities.
     *
     * @param capabilities                   is an instance of {@link Capabilities}.
     * @param autoQuoteCapabilitiesOnWindows automatically escape quote all
     *                                       capabilities when calling appium.
     *                                       This is required on windows systems only.
     * @return the self-reference.
     */
    public AppiumServiceBuilder withCapabilities(Capabilities capabilities,
                                                 boolean autoQuoteCapabilitiesOnWindows) {
        this.autoQuoteCapabilitiesOnWindows = autoQuoteCapabilitiesOnWindows;
        return withCapabilities(capabilities);
    }

    /**
     * Sets an executable appium.js.
     *
     * @param appiumJS an executable appium.js (1.4.x and lower) or
     *                 main.js (1.5.x and higher).
     * @return the self-reference.
     */
    public AppiumServiceBuilder withAppiumJS(File appiumJS) {
        this.appiumJS = appiumJS;
        return this;
    }

    public AppiumServiceBuilder withIPAddress(String ipAddress) {
        this.ipAddress = ipAddress;
        return this;
    }

    @Nullable
    private static File loadPathFromEnv(String envVarName) {
        String fullPath = System.getProperty(envVarName);
        if (isNullOrEmpty(fullPath)) {
            fullPath = System.getenv(envVarName);
        }
        return isNullOrEmpty(fullPath) ? null : new File(fullPath);
    }

    private void loadPathToMainScript() {
        if (this.appiumJS != null) {
            validatePath(this.appiumJS.getAbsolutePath(), APPIUM_JS_NOT_EXIST_ERROR.apply(this.appiumJS));
            return;
        }

        File mainScript = loadPathFromEnv(APPIUM_PATH);
        if (mainScript != null) {
            validatePath(mainScript.getAbsolutePath(), APPIUM_JS_NOT_EXIST_ERROR.apply(mainScript));
            this.appiumJS = mainScript;
            return;
        }

        this.appiumJS = findMainScript();
    }

    private String capabilitiesToQuotedCmdlineArg() {
        if (capabilities == null) {
            return "{}";
        }
        StringBuilder result = new StringBuilder();
        Map<String, ?> capabilitiesMap = capabilities.asMap();
        Set<? extends Map.Entry<String, ?>> entries = capabilitiesMap.entrySet();

        for (Map.Entry<String, ?> entry : entries) {
            Object value = entry.getValue();

            if (value == null) {
                continue;
            }

            if (value instanceof String) {
                String valueString = (String) value;
                if (PATH_CAPABILITIES.contains(entry.getKey())) {
                    value = "\\\"" + valueString.replace("\\", "/") + "\\\"";
                } else {
                    value = "\\\"" + valueString + "\\\"";
                }
            } else {
                value = String.valueOf(value);
            }

            String key = "\\\"" + entry.getKey() + "\\\"";
            if (result.length() > 0) {
                result.append(", ");
            }
            result.append(key).append(": ").append(value);
        }

        return "{" + result + "}";
    }

    private String capabilitiesToCmdlineArg() {
        if (autoQuoteCapabilitiesOnWindows && Platform.getCurrent().is(Platform.WINDOWS)) {
            return capabilitiesToQuotedCmdlineArg();
        }
        Gson gson = new GsonBuilder()
                .disableHtmlEscaping()
                .serializeNulls()
                .create();
        // Selenium internally uses org.apache.commons.exec.CommandLine
        // which has the following known bug in its arguments parser:
        // https://issues.apache.org/jira/browse/EXEC-54
        return gson.toJson(capabilities.asMap());
    }

    @Override
    protected List<String> createArgs() {
        List<String> argList = new ArrayList<>();
        loadPathToMainScript();
        argList.add(appiumJS.getAbsolutePath());
        argList.add("--port");
        argList.add(String.valueOf(getPort()));

        if (isNullOrEmpty(ipAddress)) {
            ipAddress = BROADCAST_IP4_ADDRESS;
        }
        argList.add("--address");
        argList.add(ipAddress);

        File log = getLogFile();
        if (log != null) {
            argList.add("--log");
            argList.add(log.getAbsolutePath());
        }

        Set<Map.Entry<String, String>> entries = serverArguments.entrySet();
        for (Map.Entry<String, String> entry : entries) {
            String argument = entry.getKey();
            String value = entry.getValue();
            if (isNullOrEmpty(argument) || value == null) {
                continue;
            }

            argList.add(argument);
            if (!isNullOrEmpty(value)) {
                argList.add(value);
            }
        }

        if (capabilities != null) {
            argList.add("--default-capabilities");
            argList.add(capabilitiesToCmdlineArg());
        }

        return new ImmutableList.Builder<String>().addAll(argList).build();
    }

    @Override
    protected void loadSystemProperties() {
        File driverExecutable = ReflectionHelpers.getPrivateFieldValue(
                DriverService.Builder.class, this, "exe", File.class
        );
        if (driverExecutable == null) {
            usingDriverExecutable(findDefaultExecutable());
        }
    }

    /**
     * Sets which Node.js the builder will use.
     *
     * @param nodeJSExecutable The executable Node.js to use.
     * @return A self reference.
     */
    @Override
    public AppiumServiceBuilder usingDriverExecutable(File nodeJSExecutable) {
        return super.usingDriverExecutable(nodeJSExecutable);
    }

    /**
     * Sets which port the appium server should be started on. A value of 0 indicates that any
     * free port may be used.
     *
     * @param port The port to use; must be non-negative.
     * @return A self reference.
     */
    @Override
    public AppiumServiceBuilder usingPort(int port) {
        return super.usingPort(port);
    }

    /**
     * Configures the appium server to start on any available port.
     *
     * @return A self reference.
     */
    @Override
    public AppiumServiceBuilder usingAnyFreePort() {
        return super.usingAnyFreePort();
    }

    /**
     * Defines the environment for the launched appium server.
     *
     * @param environment A map of the environment variables to launch the
     *                    appium server with.
     * @return A self reference.
     */
    @Override
    public AppiumServiceBuilder withEnvironment(Map<String, String> environment) {
        return super.withEnvironment(environment);
    }

    /**
     * Configures the appium server to write log to the given file.
     *
     * @param logFile A file to write log to.
     * @return A self reference.
     */
    @Override
    public AppiumServiceBuilder withLogFile(File logFile) {
        return super.withLogFile(logFile);
    }

    @SneakyThrows
    @Override
    protected AppiumDriverLocalService createDriverService(File nodeJSExecutable, int nodeJSPort,
                                                           Duration startupTimeout,
                                                           List<String> nodeArguments,
                                                           Map<String, String> nodeEnvironment) {
        String basePath = serverArguments.getOrDefault(
                GeneralServerFlag.BASEPATH.getArgument(), serverArguments.get("-pa"));
        return new AppiumDriverLocalService(ipAddress, nodeJSExecutable, nodeJSPort, startupTimeout, nodeArguments,
                nodeEnvironment).withBasePath(basePath);
    }
}