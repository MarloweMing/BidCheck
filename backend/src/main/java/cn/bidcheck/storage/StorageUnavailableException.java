package cn.bidcheck.storage;

import java.io.IOException;

/** Only fixed, safe user messages may be passed here; never include SDK diagnostics. */
public final class StorageUnavailableException extends IOException {
    public StorageUnavailableException(String safeMessage) { super(safeMessage); }
}
