import java.awt.AWTException;
import java.awt.Image;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.Toolkit;
import java.awt.TrayIcon;
import java.awt.TrayIcon.MessageType;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.prefs.Preferences;

import javax.swing.ImageIcon;
import javax.swing.JFileChooser;
import javax.swing.JOptionPane;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;
import org.jsoup.select.Elements;

public class Main {
    //ST2137, LSM1303, CS5321
    public static final int ST2137 = 0;
    public static final int LSM1303 = 1;
    public static final int CS5321 = 2;
    public static final ArrayList<String> courseNames = new ArrayList<String>();
    public static final ArrayList<String> courseIds = new ArrayList<String>();
    public static final String PREFERENCE_KEY = "IVLEDownloader";

    public static boolean isStarted = true;
    public static long nextRun = 0;
    public static Thread currentThread;
    public static Preferences prefs;
    public static String rootCourseFolder;

    //Notification Stuff
    public static final SystemTray tray = SystemTray.getSystemTray();
    public static PopupMenu popup;
    public static TrayIcon trayIcon;


    public static void main(String[] args) throws Exception {
        //Initialize Settings
        currentThread = Thread.currentThread();
        prefs = Preferences.userRoot().node(PREFERENCE_KEY);
        rootCourseFolder = prefs.get(PREFERENCE_KEY, "Invalid Directory");
        if (rootCourseFolder.equals("Invalid Directory")) {
            boolean isSuccess = promptDownloadPath();
            if (isSuccess) {
                rootCourseFolder = prefs.get(PREFERENCE_KEY, System.getProperty("user.dir"));
            } else {
                System.exit(-1);
            }
        }

        getFolderNames();
        createTrayIcon();
        boolean isStoppedButInterrupted = false;

        //Remove icon when crash
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            public void run() {
                // what you want to do
                tray.remove(trayIcon);
            }
        }));

        while (true) {
            nextRun = System.currentTimeMillis() + 600000; //600 000 ms = 10min

            if (isStarted) {
                for (int i=0; i<courseNames.size(); i++) {
                    String courseFolder = rootCourseFolder + courseNames.get(i) + "\\";
                    try {
                        downloadCourse(courseIds.get(i), courseFolder);
                    } catch (Exception e) {
                        //Ignore error
                        e.printStackTrace();
                    }
                }
            }
            System.out.println("Waiting 10 minutes...");

            //Set back to stopped in order to disable the check again
            if (isStoppedButInterrupted) {
                isStarted = false;
                isStoppedButInterrupted = false;
                System.out.println("Set isStarted back to false as it was forced");
            }

            try {
                Thread.sleep(60000*10); // 10 minutes
            } catch (Exception e) {
                System.out.println("Forced Recheck!");
                if (!isStarted) {
                    isStarted = true;
                    isStoppedButInterrupted = true;
                }
            }
        }
    }

    public static void getFolderNames() throws Exception {
        String cookies = "ivle12=" + ChromeCookieExtractor.getIvleToken();
        String url = "https://ivle.nus.edu.sg/v1/workspace.aspx";
        String params = "";
        String html = sendPOST(url, params, cookies);
        Document doc = Jsoup.parse(html);
        Elements elements = doc.select("[data-content=Click to view the module.]"); //Element that contains id and course code
        for (Element e : elements) {
            int courseIdStart = e.attr("href").indexOf("=") + 1;
            String courseId = e.attr("href").substring(courseIdStart);
            String courseName = e.ownText();
            if (!courseId.isEmpty() && !courseName.isEmpty()) {
                courseIds.add(courseId);
                courseNames.add(courseName);
            }
        }

        int i = 0;
        elements = doc.select("div.panel-body > div.row > div.col-md-12");
        for (Element e : elements) {
            String folderName = e.childNode(2).toString();
            folderName = Parser.unescapeEntities(folderName, true);
            folderName = toCamelCase(folderName);
            folderName = folderName.trim().replace("\u00a0", ""); //Removing all forms of whitespace
            if (!folderName.isEmpty()) {
                String newFolderName = courseNames.get(i) + " " + folderName;
                courseNames.set(i, newFolderName);
                i++;
            }
        }
    }

    public static String toCamelCase(final String init) {
        if (init==null)
            return null;

        final StringBuilder ret = new StringBuilder(init.length());

        for (final String word : init.split(" ")) {
            if (!word.isEmpty()) {
                ret.append(Character.toUpperCase(word.charAt(0)));
                ret.append(word.substring(1).toLowerCase());
            }
            if (!(ret.length()==init.length()))
                ret.append(" ");
        }

        return ret.toString();
    }

    //Obtain the image URL
    protected static Image createImage(String path, String description) {
        URL imageURL = Main.class.getResource(path);

        if (imageURL == null) {
            System.err.println("Resource not found: " + path);
            return null;
        } else {
            return (new ImageIcon(imageURL, description)).getImage();
        }
    }

    public static void createTrayIcon() {
        //Check the SystemTray is supported
        if (!SystemTray.isSupported()) {
            System.out.println("SystemTray is not supported");
            return;
        }
        popup = new PopupMenu();
        trayIcon = new TrayIcon(createImage("icon.png", "IVLE Tray Icon"));
        trayIcon.setImageAutoSize(true);

        // Create a pop-up menu components
        MenuItem timeLeftItem = new MenuItem("");
        timeLeftItem.setEnabled(false);
        MenuItem startStopItem = new MenuItem("Stop"); //Since we start on initialization
        MenuItem forceCheckItem = new MenuItem("Force Recheck");
        MenuItem changeDirectoryItem = new MenuItem("Change Directory");
        MenuItem exitItem = new MenuItem("Exit");

        //Action Listeners for buttons
        trayIcon.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (isStarted) {
                    long timeLeft = (nextRun - System.currentTimeMillis()) / (1000 * 60);
                    timeLeftItem.setLabel("Rechecking in " + timeLeft + "m");
                } else {
                    timeLeftItem.setLabel("Stopped");
                }
            }
        });
        forceCheckItem.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                currentThread.interrupt();
            }
        });
        startStopItem.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (isStarted) {
                    isStarted = false;
                    startStopItem.setLabel("Start");
                    long timeLeft = (nextRun - System.currentTimeMillis()) / (1000 * 60);
                    timeLeftItem.setLabel("Rechecking in " + timeLeft + "m");
                } else {
                    isStarted = true;
                    startStopItem.setLabel("Stop");
                    timeLeftItem.setLabel("Stopped");
                }
            }
        });
        changeDirectoryItem.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                promptDownloadPath();
            }
        });
        exitItem.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                System.exit(0);
            }
        });

        //Add components to pop-up menu
        popup.add(timeLeftItem);
        popup.add(forceCheckItem);
        popup.add(startStopItem);
        popup.addSeparator();
        popup.add(changeDirectoryItem);
        popup.add(exitItem);
        trayIcon.setPopupMenu(popup);

        try {
            tray.add(trayIcon);
        } catch (AWTException e) {
            System.out.println("TrayIcon could not be added.");
        }
    }

    private static boolean promptDownloadPath() {
        JFileChooser fc = new JFileChooser();
        fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        fc.setDialogTitle("Select folder to store IVLE files...");
        int result = fc.showOpenDialog(null);
        if (result == JFileChooser.APPROVE_OPTION) {
            String newDownloadPath = fc.getSelectedFile().getAbsolutePath() + "\\";
            System.out.println("Changed path to " + newDownloadPath);
            prefs.put(PREFERENCE_KEY, newDownloadPath);
            rootCourseFolder = newDownloadPath;
            JOptionPane.showMessageDialog(null, "Directory changed!");
            return true;
        } else {
            JOptionPane.showMessageDialog(null, "Directory not changed!");
            return false;
        }
    }

    public static void displayNotification(String message) throws Exception {
        if (SystemTray.isSupported()) {
            //Obtain only one instance of the SystemTray object
            SystemTray tray = SystemTray.getSystemTray();

            //If the icon is a file
            Image image = Toolkit.getDefaultToolkit().createImage("icon.png");
            //Alternative (if the icon is on the classpath):
            //Image image = Toolkit.getToolkit().createImage(getClass().getResource("icon.png"));
            TrayIcon trayIcon = new TrayIcon(image, "Tray Demo");
            //Let the system resizes the image if needed
            trayIcon.setImageAutoSize(true);
            //Set tooltip text for the tray icon
            trayIcon.setToolTip("IVLE File Downloader");
            tray.add(trayIcon);
            trayIcon.displayMessage("IVLE File Downloader", message, MessageType.INFO);
        }
    }

    public static void downloadCourse(String courseId, String courseFolder) throws Exception {
        //Modify-able
        String cookies = "ivle12=" + ChromeCookieExtractor.getIvleToken();

        //Links and cookies
        String rootUrl = "https://ivle.nus.edu.sg/v1/File/Student/";
        String downloadUrl = "https://ivle.nus.edu.sg";

        //Get folder ids from a course
        System.out.println("Retrieving folders from course: " + courseId);
        String url = rootUrl + "GetFileList.ashx?CourseID=" + courseId + "&WorkbinID=&folderID=";
        String params = "";
        String html = sendPOST(url, params, cookies);
        Document folderDoc = Jsoup.parse(html);
        Elements folderElement = folderDoc.getElementsByClass("fName");

        //Get file from each folder
        for (Element e : folderElement) {
            System.out.println("  Retrieving files from folder: " + e.text());
            String subFolder = e.text() + "\\";
            String subFolderUrl = e.attr("href").replaceFirst("default.aspx", "GetFileList.ashx");

            if (!subFolderUrl.isEmpty()) {
                url =  rootUrl + subFolderUrl;
                html = sendPOST(url, params, cookies);
                Document fileDoc = Jsoup.parse(html);
                Elements fileElements = fileDoc.getElementsByClass("fileName");
                for (Element e2 : fileElements) {
                    //System.out.println("    " + e2.parent().siblingElements().select(".text-center").text());
                    String fileName = e2.text().trim();
                    String fileLink = downloadUrl + e2.attr("href");
                    String downloadLocation = courseFolder + subFolder + fileName;

                    //Download the file if exists and valid, also ensure file does not exists in the first place
                    if (!fileName.isEmpty() && !fileLink.isEmpty()) {
                        //Create directory if not exist
                        File file = new File(downloadLocation);
                        Files.createDirectories(Paths.get(file.getParent()));

                        //Only download if the file does not exist //TODO: to change to database saving, otherwise cannot rename files
                        if (!file.exists()) {
                            System.out.println("    Downloading \"" + fileName + "\" to " + downloadLocation);

                            //Download using another thread
                            Thread download = new Thread(){
                                public void run(){
                                    try {
                                        downloadFromUrl(fileLink, downloadLocation, cookies);
                                    } catch (Exception e) {
                                        e.printStackTrace();
                                    }
                                }
                            };
                            download.start();//start the thread

                            displayNotification(fileName + " downloaded to " + downloadLocation);
                        } else {
                            System.out.println("    \"" + fileName + "\" already exists, skipping");
                        }
                    }
                }
            }
        }
        System.out.println("Done!");
    }

    public static void downloadFromUrl(String urlString, String localFilename, String cookies) throws Exception {
        InputStream is = null;
        FileOutputStream fos = null;

        try {
            URL url = new URL(urlString);
            URLConnection urlConn = url.openConnection();//connect
            urlConn.setRequestProperty("Cookie", cookies);

            is = urlConn.getInputStream();               //get connection inputstream
            fos = new FileOutputStream(localFilename);   //open outputstream to local file

            byte[] buffer = new byte[4096];              //declare 4KB buffer
            int len;

            //while we have availble data, continue downloading and storing to local file
            while ((len = is.read(buffer)) > 0) {
                fos.write(buffer, 0, len);
            }
        } finally {
            try {
                if (is != null) {
                    is.close();
                }
            } finally {
                if (fos != null) {
                    fos.close();
                }
            }
        }
    }

    private static String sendGET(String url) throws Exception {
        URL obj = new URL(url);
        HttpURLConnection con = (HttpURLConnection) obj.openConnection();
        con.setRequestMethod("GET");
        int responseCode = con.getResponseCode();
        if (responseCode == HttpURLConnection.HTTP_OK) { // success
            BufferedReader in = new BufferedReader(new InputStreamReader(con.getInputStream()));
            String inputLine;
            StringBuffer response = new StringBuffer();

            while ((inputLine = in.readLine()) != null) {
                response.append(inputLine);
            }
            in.close();

            // return result
            return response.toString();
        } else {
            return null;
        }

    }

    private static String sendPOST(String url, String params, String cookies) throws Exception {
        URL obj = new URL(url);
        HttpURLConnection con = (HttpURLConnection) obj.openConnection();
        con.setRequestMethod("POST");
        con.setRequestProperty("Cookie", cookies);

        // For POST only - START
        con.setDoOutput(true);
        OutputStream os = con.getOutputStream();
        os.write(params.getBytes());
        os.flush();
        os.close();
        // For POST only - END

        int responseCode = con.getResponseCode();

        if (responseCode == HttpURLConnection.HTTP_OK) { // success
            BufferedReader in = new BufferedReader(new InputStreamReader(con.getInputStream(), StandardCharsets.UTF_8)); //To read special characters such as "–" becomes "â€“Â"
            String inputLine;
            StringBuffer response = new StringBuffer();

            while ((inputLine = in.readLine()) != null) {
                response.append(inputLine);
            }
            in.close();

            // print result
            return response.toString();
        } else {
            return null;
        }
    }
}
