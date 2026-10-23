package cm.sji.innova.desktop;

import org.junit.jupiter.api.Test;

class DesktopStartupTest {
    @Test
    void startsWithFlatLafWithoutADisplay() throws Exception {
        DesktopApplication.main(new String[] {"--smoke-test"});
    }
}
