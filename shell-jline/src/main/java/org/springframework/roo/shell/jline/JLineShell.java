package org.springframework.roo.shell.jline;


import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.Logger;

import jline.ANSIBuffer;
import jline.ConsoleReader;
import jline.WindowsTerminal;
import jline.ANSIBuffer.ANSICodes;

import org.springframework.roo.shell.AbstractShell;
import org.springframework.roo.shell.CommandMarker;
import org.springframework.roo.shell.ExitShellRequest;
import org.springframework.roo.shell.Shell;
import org.springframework.roo.shell.event.ShellStatus;
import org.springframework.roo.shell.event.ShellStatus.Status;
import org.springframework.roo.shell.event.ShellStatusListener;
import org.springframework.roo.support.util.Assert;
import org.springframework.roo.support.util.ClassUtils;

/**
 * Uses the feature-rich <a href="http://jline.sourceforge.net/">JLine</a> library to provide an interactive shell.
 * 
 * <p>
 * Due to Windows' lack of color ANSI services out-of-the-box, this implementation automatically detects the classpath
 * presence of <a href="http://jansi.fusesource.org/">Jansi</a> and uses it if present. This library is not necessary 
 * for *nix machines, which support colour ANSI without any special effort. This implementation has been written to 
 * use reflection in order to avoid hard dependencies on Jansi.
 * 
 * @author Ben Alex
 * @since 1.0
 */
public abstract class JLineShell extends AbstractShell implements CommandMarker, Shell, Runnable {
	private static final String ANSI_CONSOLE_CLASSNAME = "org.fusesource.jansi.AnsiConsole";
	private static final boolean JANSI_AVAILABLE = ClassUtils.isPresent(ANSI_CONSOLE_CLASSNAME, JLineShell.class.getClassLoader());
	private static final boolean FLASH_MESSAGE_DISABLED = Boolean.getBoolean("flash.message.disabled");
	
    private ConsoleReader reader;
    private boolean developmentMode = false;
    private FileWriter fileLog;
	private DateFormat df = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
	protected ShellStatusListener statusListener; // ROO-836
	/** key: slot name, value: flashInfo instance */
	private Map<String, FlashInfo> flashInfoMap = new HashMap<String, FlashInfo>();
	/** key: row number, value: eraseLineFromPosition */
	private Map<Integer,Integer> rowErasureMap = new HashMap<Integer,Integer>();
	
	public void run() {
		try {
			if (JANSI_AVAILABLE && JLineLogHandler.WINDOWS_OS) {
				try {
					reader = createAnsiWindowsReader();
				} catch (Exception e) {
					// Try again using default ConsoleReader constructor 
					logger.warning("Can't initialize jansi AnsiConsole, falling back to default: " + e);
				}
			}
			if (reader == null) reader = new ConsoleReader();
		} catch (IOException ioe) {
			throw new IllegalStateException("Cannot start console class", ioe);
		}
		
		setPromptPath(null);
		
        JLineLogHandler handler = new JLineLogHandler(reader, this);
        JLineLogHandler.prohibitRedraw(); // affects this thread only
		Logger mainLogger = Logger.getLogger("");
        removeHandlers(mainLogger);
        mainLogger.addHandler(handler);

        reader.addCompletor(new JLineCompletorAdapter(getParser()));
		
		reader.setBellEnabled(true);
		if (Boolean.getBoolean("jline.nobell")) {
        	reader.setBellEnabled(false);
		}
                
		// reader.setDebug(new PrintWriter(new FileWriter("writer.debug", true)));
		
		openFileLogIfPossible();
		
		flashMessageRenderer();
		
        logger.info(version(null));
        logger.info("Welcome to Spring Roo. For assistance press " + completionKeys + " or type \"hint\" then hit ENTER.");
        
        setShellStatus(Status.STARTED);

        // Handle any "execute-then-quit" operation
        String rooArgs = System.getProperty("roo.args");
        if (rooArgs != null && !"".equals(rooArgs)) {
            setShellStatus(Status.USER_INPUT);
        	boolean success = executeCommand(rooArgs);
            if (exitShellRequest == null) {
            	// The command itself did not specify an exit shell code, so we'll fall back to something sensible here
                executeCommand("quit"); // ROO-839
               	exitShellRequest = success ? ExitShellRequest.NORMAL_EXIT : ExitShellRequest.FATAL_EXIT;
            }
            setShellStatus(Status.SHUTTING_DOWN);
        } else {
            // Normal RPEL processing
        	promptLoop();
        }
	}

	private void removeHandlers(Logger l) {
		Handler[] handlers = l.getHandlers();
		if (handlers != null && handlers.length > 0) {
			for (Handler h : handlers) {
				l.removeHandler(h);
			}
		}
	}
	
	public void stop() {}
	
	@Override 
	public void setPromptPath(String path) {
		if (reader.getTerminal().isANSISupported()) {
			ANSIBuffer ansi = JLineLogHandler.getANSIBuffer();
			if ("".equals(path) || path == null) {
				shellPrompt = ansi.yellow("roo> ").toString();
			} else {
				shellPrompt = ansi.cyan(path).yellow(" roo> ").toString();
			}
		} else {
			// the superclass will do for this non-ANSI terminal
			super.setPromptPath(path);
		}

		// the shellPrompt is now correct; let's ensure it now gets used
		reader.setDefaultPrompt(JLineShell.shellPrompt);
	}

	private ConsoleReader createAnsiWindowsReader() throws Exception {
		// Get decorated OutputStream that parses ANSI-codes
		final PrintStream ansiOut = (PrintStream) ClassUtils.forName(ANSI_CONSOLE_CLASSNAME, JLineShell.class.getClassLoader()).getMethod("out").invoke(null);
		WindowsTerminal ansiTerminal = new WindowsTerminal() {
			public boolean isANSISupported() {
				return true;
			}
		};
		ansiTerminal.initializeTerminal();
		// Make sure to reset the original shell's colors on shutdown by closing the stream
		statusListener = new ShellStatusListener() {
			public void onShellStatusChange(ShellStatus oldStatus, ShellStatus newStatus) {
				if (newStatus.getStatus().equals(Status.SHUTTING_DOWN)) {
					ansiOut.close();
				}
			}
		};
		addShellStatusListener(statusListener);
		return new ConsoleReader(
			new FileInputStream(FileDescriptor.in),
        	new PrintWriter(
        		new OutputStreamWriter(ansiOut,
        			// Default to Cp850 encoding for Windows console output (ROO-439)
        			System.getProperty("jline.WindowsTerminal.output.encoding", "Cp850"))),
    		null,
			ansiTerminal
		);
	}
	
	private void flashMessageRenderer() {
		if (!reader.getTerminal().isANSISupported() || FLASH_MESSAGE_DISABLED) {
			return;
		}
		// Setup a thread to ensure flash messages are displayed and cleared correctly
		Thread t = new Thread(new Runnable() {
			public void run() {
				while (!shellStatus.getStatus().equals(Status.SHUTTING_DOWN)) {
					synchronized (flashInfoMap) {
						long now = System.currentTimeMillis();
						
						Set<String> toRemove = new HashSet<String>();
						for (String slot : flashInfoMap.keySet()) {
							FlashInfo flashInfo = flashInfoMap.get(slot);
							
							if (flashInfo.flashMessageUntil < now) {
								// Message has expired, so clear it
								toRemove.add(slot);
								doAnsiFlash(flashInfo.rowNumber, Level.ALL, "");
							} else {
								// The expiration time for this message has not been reached, so preserve it
								doAnsiFlash(flashInfo.rowNumber, flashInfo.flashLevel, flashInfo.flashMessage);
							}
						}
						for (String slot : toRemove) {
							flashInfoMap.remove(slot);
						}
					}
					try {
						Thread.sleep(200);
					} catch (InterruptedException ignore) {}
				}
			}
		}, "Flash message manager");
		t.start();
	}
	
	public void flash(Level level, String message, String slot) {
		Assert.notNull(level, "Level is required for a flash message");
		Assert.notNull(message, "Message is required for a flash message");
		Assert.hasText(slot, "Slot name must be specified for a flash message");
		if ((reader !=null && !reader.getTerminal().isANSISupported()) || FLASH_MESSAGE_DISABLED) {
			super.flash(level, message, slot);
			return;
		}
		synchronized (flashInfoMap) {
			FlashInfo flashInfo = flashInfoMap.get(slot);
			
			if ("".equals(message)) {
				// Request to clear the message, but give the user some time to read it first
				if (flashInfo == null) {
					// We didn't have a record of displaying it in the first place, so just quit
					return;
				}
	    		flashInfo.flashMessageUntil = System.currentTimeMillis() + 1500;
			} else {
	    		// Display this message displayed until further notice
				if (flashInfo == null) {
					// Find a row for this new slot; we basically take the first line number we discover
					flashInfo = new FlashInfo();
					flashInfo.rowNumber = Integer.MAX_VALUE;
					outer:
					for (int i = 1; i < Integer.MAX_VALUE; i++) {
						for (FlashInfo existingFlashInfo : flashInfoMap.values()) {
							if (existingFlashInfo.rowNumber == i) {
								// Veto, let's try the new candidate row number
								continue outer;
							}
						}
						// If we got to here, nobody owns this row number, so use it
						flashInfo.rowNumber = i;
						break outer;
					}
					
					// Store it
					flashInfoMap.put(slot, flashInfo);
				}
				// Populate the instance with the latest data
				flashInfo.flashMessageUntil = Long.MAX_VALUE;
				flashInfo.flashLevel = level;
				flashInfo.flashMessage = message;

				// Display right now
				doAnsiFlash(flashInfo.rowNumber, flashInfo.flashLevel, flashInfo.flashMessage);
			}
		}
	}

	// Externally synchronized via the two calling methods having a mutex on flashInfoMap
	private void doAnsiFlash(int row, Level level, String message) {
		ANSIBuffer buff = JLineLogHandler.getANSIBuffer();
		buff.append(ANSICodes.save());
		
		// Figure out the longest line we're presently displaying (or were) and erase the line from that position
		int mostFurtherLeftColNumber = Integer.MAX_VALUE;
		for (Integer candidate : rowErasureMap.values()) {
			if (candidate < mostFurtherLeftColNumber) {
				mostFurtherLeftColNumber = candidate;
			}
		}
		
		if (mostFurtherLeftColNumber == Integer.MAX_VALUE) {
			// There is nothing to erase
		} else {
			buff.append(ANSICodes.gotoxy(row, mostFurtherLeftColNumber));
			buff.append(ANSICodes.clreol()); // clear what was present on the line
		}
		
		if (("".equals(message))) {
			// They want the line blank; we've already achieved this if needed via the erasing above
			// Just need to record we no longer care about this line the next time doAnsiFlash is invoked
			rowErasureMap.remove(row);
		} else {
			// They want some message displayed
			int startFrom = reader.getTermwidth() - message.length() + 1;
			if (startFrom < 1) {
				startFrom = 1;
			}
			buff.append(ANSICodes.gotoxy(row, startFrom));
			buff.reverse(message);
			// Record we want to erase from this positioning next time (so we clean up after ourselves)
			rowErasureMap.put(row, startFrom);
		}
		
		buff.append(ANSICodes.restore());
		String stg = buff.toString();
		try {
			reader.printString(stg);
			reader.flushConsole();
		} catch (IOException ignore) {}
	}
	
    public void promptLoop() {
    	setShellStatus(Status.USER_INPUT);
    	String line;
    	
    	try {
            while (exitShellRequest == null && ( (line = reader.readLine() ) != null) ) {
            	JLineLogHandler.resetMessageTracking();
            	setShellStatus(Status.USER_INPUT);
            	
            	if ("".equals(line)) {
                	continue;
                }
            	
                executeCommand(line);
            }
        } catch (IOException ioe) {
        	throw new IllegalStateException("Shell line reading failure", ioe);
        }
        
        setShellStatus(Status.SHUTTING_DOWN);
    }
    
	public void setDevelopmentMode(boolean developmentMode) {
		JLineLogHandler.setIncludeThreadName(developmentMode);
		this.developmentMode = developmentMode;
	}

	public boolean isDevelopmentMode() {
		return this.developmentMode;
	}
	
	private void openFileLogIfPossible() {
		try {
			fileLog = new FileWriter("log.roo", true);
			// First write, so let's record the date and time of the first user command
			fileLog.write("// Spring Roo " + versionInfo() + " log opened at " + df.format(new Date()) + "\n");
			fileLog.flush();
		} catch (IOException ignoreIt) {}
	}
	
	@Override
	protected void logCommandToOutput(String processedLine) {
		if (fileLog == null) {
			openFileLogIfPossible();
			if (fileLog == null) {
				// still failing, so give up
				return;
			}
		}
		try {
			fileLog.write(processedLine + "\n"); // Unix line endings only from Roo
			fileLog.flush(); // So tail -f will show it's working
			if (getExitShellRequest() != null) {
				// Shutting down, so close our file (we can always reopen it later if needed)
				fileLog.write("// Spring Roo " + versionInfo() + " log closed at " + df.format(new Date()) + "\n");
				fileLog.flush();
				fileLog.close();
				fileLog = null;
			}
		} catch (IOException ignoreIt) {}
	}

	/**
	 * Obtains the "roo.home" from the system property, falling back to the current working directory if missing.
	 *
	 * @return the 'roo.home' system property
	 */
	@Override
	protected String getHomeAsString() {
		String rooHome = System.getProperty("roo.home");
		if (rooHome == null) {
			try {
				rooHome = new File(".").getCanonicalPath();
			} catch (Exception e) {
				throw new IllegalStateException(e);
			}
		}
		return rooHome;
	}
	
	/**
	 * Should be called by a subclass before deactivating the shell.
	 */
	protected void closeShell() {
		if (statusListener != null) {
			removeShellStatusListener(statusListener);
		}
	}

	private class FlashInfo {
		String flashMessage;
		long flashMessageUntil;
		Level flashLevel;
		int rowNumber;
	}
}
