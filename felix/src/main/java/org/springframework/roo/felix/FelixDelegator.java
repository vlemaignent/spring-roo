package org.springframework.roo.felix;

import java.io.PrintStream;
import java.util.logging.Level;

import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.scr.annotations.Service;
import org.apache.felix.shell.ShellService;
import org.osgi.service.component.ComponentContext;
import org.springframework.roo.shell.CliCommand;
import org.springframework.roo.shell.CliOption;
import org.springframework.roo.shell.CommandMarker;
import org.springframework.roo.shell.ExitShellRequest;
import org.springframework.roo.shell.Shell;
import org.springframework.roo.shell.converters.StaticFieldConverter;
import org.springframework.roo.shell.event.ShellStatus;
import org.springframework.roo.shell.event.ShellStatus.Status;
import org.springframework.roo.shell.event.ShellStatusListener;
import org.springframework.roo.support.logging.LoggingOutputStream;

/**
 * Delegates to commands provided via Felix's Shell API.
 * 
 * <p>
 * Also monitors the Roo Shell to determine when it wishes to shutdown. This shutdown request
 * is then passed through to Felix for processing.
 * 
 * @author Ben Alex
 *
 */
@Component(immediate=true)
@Service
public class FelixDelegator implements CommandMarker, ShellStatusListener {

	@Reference private ShellService shellService;
	@Reference private Shell rooShell;
	@Reference private StaticFieldConverter staticFieldConverter;
	private ComponentContext context;
	
	protected void activate(ComponentContext context) {
		this.context = context;
		rooShell.addShellStatusListener(this);
		staticFieldConverter.add(LogLevel.class);
		staticFieldConverter.add(PsOptions.class);
	}
	
	protected void deactivate(ComponentContext context) {
		this.context = null;
		rooShell.removeShellStatusListener(this);
		staticFieldConverter.remove(LogLevel.class);
		staticFieldConverter.remove(PsOptions.class);
	}
	
	@CliCommand(value="felix shell", help="Passes a command directly through to the Felix shell infrastructure")
	public void shell(@CliOption(key="", mandatory=false, specifiedDefaultValue="help", unspecifiedDefaultValue="help", help="The command to pass to Felix (WARNING: no validation or security checks are performed)") String commandLine) throws Exception {
		perform(commandLine);
	}

	@CliCommand(value="osgi log", help="Displays the OSGi log information")
	public void log(
			@CliOption(key="maximumEntries", mandatory=false, help="The maximum number of log messages to display") Integer maximumEntries,
			@CliOption(key="level", mandatory=false, help="The minimum level of messages to display") LogLevel logLevel) throws Exception {
		StringBuilder sb = new StringBuilder();
		sb.append("log");
		if (maximumEntries != null) {
			sb.append(" ").append(maximumEntries);
		}
		if (logLevel != null) {
			sb.append(" ").append(logLevel.getFelixCode());
		}
		perform(sb.toString());
	}

	@CliCommand(value="osgi ps", help="Displays OSGi bundle information")
	public void log(@CliOption(key="format", mandatory=false, specifiedDefaultValue="BUNDLE_NAME", unspecifiedDefaultValue="BUNDLE_NAME", help="The format of bundle information") PsOptions format) throws Exception {
		StringBuilder sb = new StringBuilder();
		sb.append("ps");
		if (format != null) {
			sb.append(format.getFelixCode());
		}
		perform(sb.toString());
	}

	@CliCommand(value="osgi find", help="Finds bundles by name")
	public void find(@CliOption(key="bundleSymbolicName", mandatory=true, help="A bundle symbolic name to find") BundleSymbolicName bsn) throws Exception {
		perform("find " + bsn.getKey());
	}

	@CliCommand(value="osgi headers", help="Display headers for a specific bundle")
	public void headers(@CliOption(key="bundleSymbolicName", mandatory=false, help="Limit results to a specific bundle symbolic name") BundleSymbolicName bsn) throws Exception {
		if (bsn == null) {
			perform("headers");
		} else {
			perform("headers " + bsn.findBundleIdWithoutFail(context.getBundleContext()));
		}
	}

	@CliCommand(value="osgi install", help="Installs a bundle JAR from a given URL")
	public void install(@CliOption(key="url", mandatory=true, help="The URL to obtain the bundle from") String url) throws Exception {
		perform("install " + url);
	}

	@CliCommand(value="osgi uninstall", help="Uninstalls a specific bundle")
	public void uninstall(@CliOption(key="bundleSymbolicName", mandatory=true, help="The specific bundle to uninstall") BundleSymbolicName bsn) throws Exception {
		perform("uninstall " + bsn.findBundleIdWithoutFail(context.getBundleContext()));
	}

	@CliCommand(value="osgi update", help="Updates a specific bundle")
	public void update(
			@CliOption(key="bundleSymbolicName", mandatory=true, help="The specific bundle to update ") BundleSymbolicName bsn,
			@CliOption(key="url", mandatory=false, help="The URL to obtain the updated bundle from") String url) throws Exception {
		Long id = bsn.findBundleIdWithoutFail(context.getBundleContext());
		if (url == null) {
			perform("update " + id);
		} else {
			perform("update " + id + " " + url);
		}
	}

	@CliCommand(value="osgi resolve", help="Resolves a specific bundle ID")
	public void resolve(@CliOption(key="bundleSymbolicName", mandatory=true, help="The specific bundle to resolve") BundleSymbolicName bsn) throws Exception {
		perform("resolve " + bsn.findBundleIdWithoutFail(context.getBundleContext()));
	}

	@CliCommand(value="osgi version", help="Displays OSGi framework version")
	public void version() throws Exception {
		perform("version");
	}
	
	@CliCommand(value="osgi scr list", help="Lists all SCR-defined components")
	public void scrList(@CliOption(key="bundleId", mandatory=false, help="Limit results to a specific bundle") BundleSymbolicName bsn) throws Exception {
		if (bsn == null) {
			perform("scr list");
		} else {
			perform("scr list " + bsn.findBundleIdWithoutFail(context.getBundleContext()));
		}
	}

	@CliCommand(value="osgi scr info", help="Lists information about a specific SCR-defined component")
	public void scrInfo(@CliOption(key="componentId", mandatory=true, help="The specific component identifier (use 'osgi scr list' to list component identifiers)") Integer id) throws Exception {
		perform("scr info " + id);
	}

	@CliCommand(value="osgi scr enable", help="Enables a specific SCR-defined component")
	public void scrEnable(@CliOption(key="componentId", mandatory=true, help="The specific component identifier (use 'osgi scr list' to list component identifiers)") Integer id) throws Exception {
		perform("scr enable " + id);
	}

	@CliCommand(value="osgi scr disable", help="Disables a specific SCR-defined component")
	public void scrDisable(@CliOption(key="componentId", mandatory=true, help="The specific component identifier (use 'osgi scr list' to list component identifiers)") Integer id) throws Exception {
		perform("scr disable " + id);
	}

	@CliCommand(value="osgi scr config", help="Lists the current SCR configuration")
	public void scrConfig() throws Exception {
		perform("scr config");
	}

	@CliCommand(value="osgi obr url list", help="Lists the currently-configured OSGi Bundle Repository (OBR) repository file URLs")
	public void obrUrlList() throws Exception {
		perform("obr list-url");
	}

	@CliCommand(value="osgi obr url add", help="Adds a new OSGi Bundle Repository (OBR) repository file URL")
	public void obrUrlAdd(@CliOption(key="url", mandatory=true, help="The URL to add (eg http://felix.apache.org/obr/releases.xml)") String url) throws Exception {
		perform("obr add-url " + url);
	}

	@CliCommand(value="osgi obr url remove", help="Removes an existing OSGi Bundle Repository (OBR) repository file URL")
	public void obrUrlRemove(@CliOption(key="url", mandatory=true, help="The URL to remove (list existing URLs via 'osgi obr url list')") String url) throws Exception {
		perform("obr remove-url " + url);
	}

	@CliCommand(value="osgi obr url refresh", help="Refreshes an existing OSGi Bundle Repository (OBR) repository file URL")
	public void obrUrlRefresh(@CliOption(key="url", mandatory=true, help="The URL to refresh (list existing URLs via 'osgi obr url list')") String url) throws Exception {
		perform("obr refresh-url " + url);
	}

	@CliCommand(value="osgi obr list", help="Lists all available bundles from the OSGi Bundle Repository (OBR) system")
	public void obrList(@CliOption(key="keywords", mandatory=false, help="Keywords to locate") String keywords) throws Exception {
		StringBuilder sb = new StringBuilder();
		sb.append("obr list -v");
		if (keywords != null) {
			sb.append(" ").append(keywords);
		}
		perform(sb.toString());
	}

	@CliCommand(value="osgi obr info", help="Displays information on a specific OSGi Bundle Repository (OBR) bundle")
	public void obrInfo(@CliOption(key="bundleSymbolicName", mandatory=true, optionContext="obr", help="The specific bundle to display information for") BundleSymbolicName bsn) throws Exception {
		perform("obr info " + bsn.getKey());
	}

	@CliCommand(value="osgi obr deploy", help="Deploys a specific OSGi Bundle Repository (OBR) bundle")
	public void obrDeploy(@CliOption(key="bundleSymbolicName", mandatory=true, optionContext="obr", help="The specific bundle to deploy") BundleSymbolicName bsn) throws Exception {
		perform("obr deploy " + bsn.getKey());
	}

	@CliCommand(value="osgi obr start", help="Starts a specific OSGi Bundle Repository (OBR) bundle")
	public void obrStart(@CliOption(key="bundleSymbolicName", mandatory=true, optionContext="obr", help="The specific bundle to start") BundleSymbolicName bsn) throws Exception {
		perform("obr start " + bsn.getKey());
	}

	private void perform(String commandLine) throws Exception {
		LoggingOutputStream sysOut = new LoggingOutputStream(Level.INFO);
		LoggingOutputStream sysErr = new LoggingOutputStream(Level.SEVERE);
		sysOut.setSourceClassName(FelixDelegator.class.getName());
		sysErr.setSourceClassName(FelixDelegator.class.getName());
		PrintStream printStreamOut = new PrintStream(sysOut);
		PrintStream printErrOut = new PrintStream(sysErr);
		try {
			shellService.executeCommand(commandLine, printStreamOut, printErrOut);
		} finally {
			printStreamOut.close();
			printErrOut.close();
		}
	}
	
	@CliCommand(value={"exit", "quit"}, help="Exits the shell")
	public ExitShellRequest quit() {
		return ExitShellRequest.NORMAL_EXIT;
	}

	public void onShellStatusChange(ShellStatus oldStatus, ShellStatus newStatus) {
		if (newStatus.getStatus().equals(Status.SHUTTING_DOWN)) {
			try {
				if (rooShell != null && rooShell.getExitShellRequest() != null) {
					// ROO-836
					System.setProperty("roo.exit", new Integer(rooShell.getExitShellRequest().getExitCode()).toString());
				}
				
				System.setProperty("developmentMode", new Boolean(rooShell.isDevelopmentMode()).toString());
				perform("shutdown");
			} catch (Exception e) {
				throw new IllegalStateException(e);
			}
		}
	}

}
