package info.nightscout.androidaps.interfaces;

import info.nightscout.androidaps.data.PumpEnactResult;

/**
 * Created by mike on 12.06.2017.
 */

public interface DanaRInterface {
    PumpEnactResult loadHistory(byte type);
}
