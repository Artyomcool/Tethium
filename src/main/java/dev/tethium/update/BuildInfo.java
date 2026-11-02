package dev.tethium.update;

import java.util.Properties;

public final class BuildInfo {
    public static final int PROTOCOL = 1;
    public static final String BUILD, HOST;
    static {
        var values = new Properties();
        try (var input = BuildInfo.class.getResourceAsStream("/tethium-build.properties")) { if (input != null) values.load(input); }
        catch (Exception e) { throw new ExceptionInInitializerError(e); }
        BUILD = values.getProperty("build", "development"); HOST = values.getProperty("host", "development");
    }
    private BuildInfo() {}
}
