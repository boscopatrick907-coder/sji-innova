package cm.sji.innova.desktop.backup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cm.sji.innova.core.persistence.SqliteDatabase;
import cm.sji.innova.core.persistence.SqliteProductImportRepository;
import cm.sji.innova.desktop.evidence.EvidenceService;
import cm.sji.innova.desktop.shell.AppContext;
import cm.sji.innova.desktop.shell.AppContextLoader;
import cm.sji.innova.desktop.shell.DataFolderCoordinator;
import cm.sji.innova.desktop.shell.LocalAppData;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipFile;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** OFF-03c4: the backup folder this computer remembers, in {@code backup-settings.properties}. */
class BackupSettingsTest {
    @TempDir Path directory;
    private LocalAppData location;
    private DataFolderCoordinator coordinator;
    private DataFolderCoordinator.Membership membership;

    @BeforeEach
    void openTheShopFolder() throws Exception {
        location = new LocalAppData(directory.resolve("data"));
        coordinator = DataFolderCoordinator.forFolder(location);
        membership = coordinator.join();
    }

    @AfterEach
    void closeTheShopFolder() throws Exception {
        membership.close();
    }

    @Test
    void nothingIsRememberedUntilAFolderIsChosenThenItIsKeptWithTheOtherSettings() throws Exception {
        BackupSettings settings = new BackupSettings(location);
        assertEquals(Optional.empty(), settings.folder(), "Nothing chosen yet");

        Files.writeString(location.directory().resolve(BackupSettings.FILE), "format=1\nlater=kept\n", StandardCharsets.UTF_8);
        settings.rememberFolder(directory.resolve("usb").resolve("..").resolve("usb"));

        Path usb = directory.resolve("usb").toAbsolutePath().normalize();
        assertEquals(Optional.of(usb), new BackupSettings(location).folder(), "Read again, as after a restart");
        String file = Files.readString(location.directory().resolve(BackupSettings.FILE), StandardCharsets.UTF_8);
        assertTrue(file.contains("later=kept"), "Another setting in the file is kept: " + file);
        try (var leftovers = Files.list(location.directory())) {
            assertEquals(List.of(), leftovers.filter(p -> p.getFileName().toString().endsWith(".tmp")).toList(), "No temporary file is left");
        }

        settings.rememberFolder(directory.resolve("other"));
        assertEquals(Optional.of(directory.resolve("other").toAbsolutePath().normalize()), settings.folder(), "A new choice replaces the old one");
    }

    @Test
    void aSettingsFileThatCannotBeReadCountsAsNoFolder() throws Exception {
        Path file = location.directory().resolve(BackupSettings.FILE);
        for (String unreadable : List.of("folder=\n", "folder=usb\n", "folder=\\uZZZZ\n", "folder=C:\\\u0000bad\n", "nothing useful\n")) {
            Files.writeString(file, "format=1\n" + unreadable, StandardCharsets.UTF_8);
            assertEquals(Optional.empty(), new BackupSettings(location).folder(), unreadable.strip());
        }
        Files.delete(file);
        Files.createDirectory(file);
        assertEquals(Optional.empty(), new BackupSettings(location).folder(), "A folder with the file's name");
    }

    @Test
    void readingAndRememberingWaitWhileABackupOrRestoreHoldsTheFolder() throws Exception {
        BackupSettings settings = new BackupSettings(location);
        try (var worker = Executors.newSingleThreadExecutor()) {
            CountDownLatch started = new CountDownLatch(2);
            Future<?> remembering;
            Future<Optional<Path>> reading;
            try (var maintenance = coordinator.beginMaintenance(Duration.ofSeconds(10))) {
                remembering = worker.submit(() -> { started.countDown(); settings.rememberFolder(directory.resolve("usb")); return null; });
                reading = worker.submit(() -> { started.countDown(); return settings.folder(); });
                assertThrows(TimeoutException.class, () -> remembering.get(300, TimeUnit.MILLISECONDS));
                assertFalse(Files.exists(location.directory().resolve(BackupSettings.FILE)), "Nothing written while the folder is held");
            }
            remembering.get(10, TimeUnit.SECONDS);
            assertEquals(Optional.of(directory.resolve("usb").toAbsolutePath().normalize()), reading.get(10, TimeUnit.SECONDS));
            assertTrue(started.await(1, TimeUnit.SECONDS));
        }
    }

    @Test
    void neverOnTheSwingThread() throws Exception {
        BackupSettings settings = new BackupSettings(location);
        List<Throwable> refusals = new ArrayList<>();
        SwingUtilities.invokeAndWait(() -> {
            try { settings.folder(); } catch (Throwable refused) { refusals.add(refused); }
            try { settings.rememberFolder(directory.resolve("usb")); } catch (Throwable refused) { refusals.add(refused); }
        });
        assertEquals(2, refusals.size());
        refusals.forEach(refused -> assertTrue(refused instanceof IllegalStateException, refused.toString()));
        assertFalse(Files.exists(location.directory().resolve(BackupSettings.FILE)));
    }

    @Test
    void theSettingsBelongToThisComputerABackupDoesNotHoldThemAndARestoreDoesNotMoveThem() throws Exception {
        AppContext shop = AppContextLoader.load(location);
        BackupSettings settings = new BackupSettings(location);
        settings.rememberFolder(directory.resolve("usb"));
        Clock clock = Clock.systemDefaultZone();
        EvidenceService evidence = new EvidenceService(location, shop.shopId(), shop.stock(), shop.sales(),
                new SqliteProductImportRepository(new SqliteDatabase(location.databaseFile())), clock);
        Path backup = new BackupService(location, shop.shopId(), shop.deviceId(), coordinator, evidence, clock)
                .create(directory.resolve("usb")).file();

        AtomicReference<List<String>> names = new AtomicReference<>();
        try (ZipFile zip = new ZipFile(backup.toFile())) {
            names.set(zip.stream().map(entry -> entry.getName()).toList());
        }
        assertTrue(names.get().contains("cella.db"), names.get().toString());
        assertFalse(names.get().stream().anyMatch(name -> name.endsWith(BackupSettings.FILE)), "Not in the backup: " + names.get());
        assertFalse(RestoreJournal.FILES.contains(BackupSettings.FILE), "A restore moves only the shop's own files");
    }
}
