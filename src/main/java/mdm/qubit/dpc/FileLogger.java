package mdm.qubit.dpc;

import android.content.Context;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import android.os.Environment;

/**
 * Simple file logger that appends messages to an internal file.
 */
public final class FileLogger {

  private static final String LOG_FILE_NAME = "provision_log.txt";

  private FileLogger() {
    // no instances
  }

  public static void log(Context context, String message) {
    if (context == null || message == null) {
      return;
    }

    File dir = context.getFilesDir();
    File logFile = new File(dir, LOG_FILE_NAME);

    writeLine(logFile, getTimestamp() + " " + message);
  }

  public static void logToDownload(Context context, String fileName, String message) {
    if (context == null || message == null || fileName == null) {
      return;
    }
    try {
      File downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
      if (downloads == null) {
        return;
      }
      if (!downloads.exists()) {
        downloads.mkdirs();
      }
      File logFile = new File(downloads, fileName);
      writeLine(logFile, getTimestamp() + " " + message);
    } catch (Exception ignore) {
      // best-effort external log
    }
  }

  private static String getTimestamp() {
    SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);
    return sdf.format(new Date());
  }

  private static void writeLine(File file, String line) {
    PrintWriter pw = null;
    try {
      FileWriter fw = new FileWriter(file, true);
      pw = new PrintWriter(fw);
      pw.println(line);
      pw.flush();
    } catch (IOException e) {
      e.printStackTrace();
    } finally {
      if (pw != null) {
        pw.close();
      }
    }
  }
}
