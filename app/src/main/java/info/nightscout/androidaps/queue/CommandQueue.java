package info.nightscout.androidaps.queue;

import android.text.Html;
import android.text.Spanned;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedList;

import info.nightscout.androidaps.MainApp;
import info.nightscout.androidaps.R;
import info.nightscout.androidaps.data.DetailedBolusInfo;
import info.nightscout.androidaps.data.Profile;
import info.nightscout.androidaps.data.PumpEnactResult;
import info.nightscout.androidaps.queue.commands.Command;
import info.nightscout.androidaps.queue.commands.CommandBolus;
import info.nightscout.androidaps.queue.commands.CommandCancelExtendedBolus;
import info.nightscout.androidaps.queue.commands.CommandCancelTempBasal;
import info.nightscout.androidaps.queue.commands.CommandExtendedBolus;
import info.nightscout.androidaps.queue.commands.CommandLoadHistory;
import info.nightscout.androidaps.queue.commands.CommandReadStatus;
import info.nightscout.androidaps.queue.commands.CommandSetProfile;
import info.nightscout.androidaps.queue.commands.CommandTempBasalAbsolute;
import info.nightscout.androidaps.queue.commands.CommandTempBasalPercent;

/**
 * Created by mike on 08.11.2017.
 *
 * DATA FLOW:
 * ---------
 *
 * (request) - > ConfigBuilder.getCommandQueue().bolus(...)
 *
 * app no longer waits for result but passes Callback
 *
 * request is added to queue, if another request of the same type already exists in queue, it's removed prior adding
 * but if request of the same type is currently executed (probably important only for bolus which is running long time), new request is declined
 * new QueueThread is created and started if current if finished
 * CommandReadStatus is added automatically before command if queue is empty
 *
 * biggest change is we don't need exec pump commands in Handler because it's finished immediately
 * command queueing if not realized by stacking in different Handlers and threads anymore but by internal queue with better control
 *
 * QueueThread calls ConfigBuilder#connect which is passed to getActivePump().connect
 * connect should be executed on background and return immediately. afterwards isConnecting() is expected to be true
 *
 * while isConnecting() == true GUI is updated by posting connection progress
 *
 * if connect is successful: isConnected() becomes true, isConnecting() becomes false
 *      CommandQueue starts calling execute() of commands. execute() is expected to be blocking (return after finish).
 *      callback with result is called after finish automatically
 * if connect failed: isConnected() becomes false, isConnecting() becomes false
 *      connect() is called again
 *
 * when queue is empty, disconnect is called
 *
 */

public class CommandQueue {
    private static Logger log = LoggerFactory.getLogger(CommandQueue.class);

    private LinkedList<Command> queue = new LinkedList<>();
    private Command performing;

    private QueueThread thread = null;

    private PumpEnactResult executingNowError() {
        PumpEnactResult result = new PumpEnactResult();
        result.success = false;
        result.enacted = false;
        result.comment = MainApp.sResources.getString(R.string.executingrightnow);
        return result;
    }

    public boolean isRunning(Command.CommandType type) {
        if (performing != null && performing.commandType == type)
            return true;
        return false;
    }

    private synchronized void removeAll(Command.CommandType type) {
        for (int i = 0; i < queue.size(); i++) {
            if (queue.get(i).commandType == type) {
                queue.remove(i);
            }
        }
    }

    private synchronized void add(Command command) {
        // inject reading of status when adding first command to the queue
        if (queue.size() == 0 && command.commandType != Command.CommandType.READSTATUS)
            queue.add(new CommandReadStatus("Queue", null));
        queue.add(command);
    }

    synchronized void pickup() {
        performing = queue.poll();
    }

    synchronized void clear() {
        performing = null;
        for (int i = 0; i < queue.size(); i++) {
            queue.get(i).cancel();
        }

        queue.clear();
    }

    public int size() {
        return queue.size();
    }

    public Command performing() {
        return performing;
    }

    public void resetPerforming() {
        performing = null;
    }

    // After new command added to the queue
    // start thread again if not already running
    private void notifyAboutNewCommand() {
        if (thread == null || thread.getState() == Thread.State.TERMINATED) {
            thread = new QueueThread(this);
            thread.start();
        }
    }

    // returns true if command is queued
    public boolean bolus(DetailedBolusInfo detailedBolusInfo, Callback callback) {
        if (isRunning(Command.CommandType.BOLUS)) {
            if (callback != null)
                callback.result(executingNowError()).run();
            return false;
        }

        // remove all unfinished boluses
        removeAll(Command.CommandType.BOLUS);

        // add new command to queue
        add(new CommandBolus(detailedBolusInfo, callback));

        notifyAboutNewCommand();

        return true;
    }

    // returns true if command is queued
    public boolean tempBasalAbsolute(double absoluteRate, int durationInMinutes, boolean enforceNew, Callback callback) {
        if (isRunning(Command.CommandType.TEMPBASAL)) {
            if (callback != null)
                callback.result(executingNowError()).run();
            return false;
        }

        // remove all unfinished 
        removeAll(Command.CommandType.TEMPBASAL);

        // add new command to queue
        add(new CommandTempBasalAbsolute(absoluteRate, durationInMinutes, enforceNew, callback));

        notifyAboutNewCommand();

        return true;
    }

    // returns true if command is queued
    public boolean tempBasalPercent(int percent, int durationInMinutes, Callback callback) {
        if (isRunning(Command.CommandType.TEMPBASAL)) {
            if (callback != null)
                callback.result(executingNowError()).run();
            return false;
        }

        // remove all unfinished 
        removeAll(Command.CommandType.TEMPBASAL);

        // add new command to queue
        add(new CommandTempBasalPercent(percent, durationInMinutes, callback));

        notifyAboutNewCommand();

        return true;
    }

    // returns true if command is queued
    public boolean extendedBolus(double insulin, int durationInMinutes, Callback callback) {
        if (isRunning(Command.CommandType.EXTENDEDBOLUS)) {
            if (callback != null)
                callback.result(executingNowError()).run();
            return false;
        }

        // remove all unfinished 
        removeAll(Command.CommandType.EXTENDEDBOLUS);

        // add new command to queue
        add(new CommandExtendedBolus(insulin, durationInMinutes, callback));

        notifyAboutNewCommand();

        return true;
    }

    // returns true if command is queued
    public boolean cancelTempBasal(boolean enforceNew, Callback callback) {
        if (isRunning(Command.CommandType.TEMPBASAL)) {
            if (callback != null)
                callback.result(executingNowError()).run();
            return false;
        }

        // remove all unfinished 
        removeAll(Command.CommandType.TEMPBASAL);

        // add new command to queue
        add(new CommandCancelTempBasal(enforceNew, callback));

        notifyAboutNewCommand();

        return true;
    }

    // returns true if command is queued
    public boolean cancelExtended(Callback callback) {
        if (isRunning(Command.CommandType.EXTENDEDBOLUS)) {
            if (callback != null)
                callback.result(executingNowError()).run();
            return false;
        }

        // remove all unfinished 
        removeAll(Command.CommandType.EXTENDEDBOLUS);

        // add new command to queue
        add(new CommandCancelExtendedBolus(callback));

        notifyAboutNewCommand();

        return true;
    }

    // returns true if command is queued
    public boolean setProfile(Profile profile, Callback callback) {
        if (isRunning(Command.CommandType.BASALPROFILE)) {
            if (callback != null)
                callback.result(executingNowError()).run();
            return false;
        }

        // remove all unfinished 
        removeAll(Command.CommandType.BASALPROFILE);

        // add new command to queue
        add(new CommandSetProfile(profile, callback));

        notifyAboutNewCommand();

        return true;
    }

    // returns true if command is queued
    public boolean readStatus(String reason, Callback callback) {
        if (isRunning(Command.CommandType.READSTATUS)) {
            if (callback != null)
                callback.result(executingNowError()).run();
            return false;
        }

        // remove all unfinished 
        removeAll(Command.CommandType.READSTATUS);

        // add new command to queue
        add(new CommandReadStatus(reason, callback));

        notifyAboutNewCommand();

        return true;
    }

    // returns true if command is queued
    public boolean loadHistory(byte type, Callback callback) {
        if (isRunning(Command.CommandType.LOADHISTORY)) {
            if (callback != null)
                callback.result(executingNowError()).run();
            return false;
        }

        // remove all unfinished 
        removeAll(Command.CommandType.LOADHISTORY);

        // add new command to queue
        add(new CommandLoadHistory(type, callback));

        notifyAboutNewCommand();

        return true;
    }

    public Spanned spannedStatus() {
        String s = "";
        if (performing != null) {
            s += "<b>" + performing.status() + "</b>";
        }
        for (int i = 0; i < queue.size(); i++) {
            if (i != 0)
                s += "<br>";
            s += queue.get(i).status();
        }
        return Html.fromHtml(s);
    }

}
