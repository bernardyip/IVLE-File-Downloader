import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import com.sun.jna.platform.win32.Crypt32Util;

public class ChromeCookieExtractor {
    public static final String cookiePath = System.getProperty("user.home") + File.separator + "AppData" + File.separator + "Local" + File.separator + "Google" + File.separator + "Chrome" + File.separator + "User Data"
            + File.separator + "Default" + File.separator + "Cookies";

    public static String getIvleToken() throws Exception {
        Connection connection = null;
        Class.forName("org.sqlite.JDBC");
        connection = DriverManager.getConnection("jdbc:sqlite:"+cookiePath);
        PreparedStatement statement = connection.prepareStatement("select encrypted_value from cookies where host_key LIKE \"%ivle%\" and name=\"ivle12\"");
        ResultSet rs = statement.executeQuery();
        rs.next(); // Go to the first record
        byte[] encryptedData = rs.getBytes(1);
        byte[] decryptedData = Crypt32Util.cryptUnprotectData(encryptedData);
        String ivleToken = new String(decryptedData);
        statement.close();
        connection.close();
        return ivleToken;
    }
}
