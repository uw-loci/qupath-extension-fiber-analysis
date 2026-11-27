package qupath.ext.fiberanalysis.analysis;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.fx.dialogs.Dialogs;

/** Error dialogs that also reach the log, so a refusal the user saw can be found in a log file later. */
public final class UserMessage {

    private static final Logger logger = LoggerFactory.getLogger(UserMessage.class);

    private UserMessage() {}

    /**
     * Logs the message at WARN, then shows it in an error dialog.
     *
     * @param title dialog title
     * @param message what went wrong and what to do
     */
    public static void error(String title, String message) {
        logger.warn("{}: {}", title, message);
        Dialogs.showErrorMessage(title, message);
    }
}
