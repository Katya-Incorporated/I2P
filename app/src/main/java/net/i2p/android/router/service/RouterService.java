package net.i2p.android.router.service;

import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.RemoteCallbackList;
import android.os.RemoteException;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.DecimalFormat;
import java.util.List;
import java.util.Properties;
import java.util.Random;

import net.i2p.android.router.R;
import net.i2p.android.router.receiver.I2PReceiver;
import net.i2p.android.router.util.Notifications;
import net.i2p.android.router.util.Util;
import net.i2p.data.DataHelper;
import net.i2p.router.Job;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.router.RouterLaunch;
import net.i2p.util.OrderedProperties;

/**
 * Runs the router
 */
public class RouterService extends Service {

    private RouterContext _context;
    private String _myDir;
    //private String _apkPath;
    private State _state = State.INIT;
    private Thread _starterThread;
    private StatusBar _statusBar;
    private Notifications _notif;
    private I2PReceiver _receiver;
    private IBinder _binder;
    private final Object _stateLock = new Object();
    private Handler _handler;
    private Runnable _updater;
    private boolean mStartCalled;
    private static final String SHARED_PREFS = "net.i2p.android.router";
    private static final String LAST_STATE = "service.lastState";
    private static final String EXTRA_RESTART = "restart";
    private static final String MARKER = "**************************************  ";

    /**
     * This is a list of callbacks that have been registered with the
     * service.  Note that this is package scoped (instead of private) so
     * that it can be accessed more efficiently from inner classes.
     */
    final RemoteCallbackList<IRouterStateCallback> mStateCallbacks
            = new RemoteCallbackList<IRouterStateCallback>();

    @Override
    public void onCreate() {
        mStartCalled = false;
        State lastState = getSavedState();
        setState(State.INIT);
        Util.d(this + " onCreate called"
                + " Saved state is: " + lastState
                + " Current state is: " + _state);

        //(new File(getFilesDir(), "wrapper.log")).delete();
        _myDir = getFilesDir().getAbsolutePath();
        // init other stuff here, delete log, etc.
        Init init = new Init(this);
        init.initialize();
        //_apkPath = init.getAPKPath();
        _statusBar = new StatusBar(this);
        // Remove stale notification icon.
        _statusBar.remove();
        _notif = new Notifications(this);
        _binder = new RouterBinder(this);
        _handler = new Handler();
        _updater = new Updater();
        if(lastState == State.RUNNING || lastState == State.ACTIVE) {
            Intent intent = new Intent(this, RouterService.class);
            intent.putExtra(EXTRA_RESTART, true);
            onStartCommand(intent, 12345, 67890);
        } else if(lastState == State.MANUAL_QUITTING) {
            synchronized(_stateLock) {
                setState(State.MANUAL_QUITTED);
                stopSelf(); // Die.
            }
        }
    }

    /**
     * NOT called by system if it restarts us after a crash
     */
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Util.d(this + " onStart called"
                + " Intent is: " + intent
                + " Flags is: " + flags
                + " ID is: " + startId
                + " Current state is: " + _state);
        mStartCalled = true;
        boolean restart = intent != null && intent.getBooleanExtra(EXTRA_RESTART, false);
        if(restart) {
            Util.d(this + " RESTARTING");
        }
        synchronized(_stateLock) {
            if(_state != State.INIT) //return START_STICKY;
            {
                return START_NOT_STICKY;
            }
            _receiver = new I2PReceiver(this);
            if(Util.isConnected(this)) {
                if(restart) {
                    _statusBar.replace(StatusBar.ICON_STARTING, "I2P is restarting");
                } else {
                    _statusBar.replace(StatusBar.ICON_STARTING, "I2P is starting up");
                }
                setState(State.STARTING);
                _starterThread = new Thread(new Starter());
                _starterThread.start();
            } else {
                _statusBar.replace(StatusBar.ICON_WAITING_NETWORK, "I2P is waiting for a network connection");
                setState(State.WAITING);
                _handler.postDelayed(new Waiter(), 10 * 1000);
            }
        }
        _handler.removeCallbacks(_updater);
        _handler.postDelayed(_updater, 50);
        if(!restart) {
            startForeground(1337, _statusBar.getNote());
        }

        //return START_STICKY;
        return START_NOT_STICKY;
    }

    /**
     * maybe this goes away when the receiver can bind to us
     */
    private class Waiter implements Runnable {

        public void run() {
            Util.d(MARKER + this + " waiter handler"
                    + " Current state is: " + _state);
            if(_state == State.WAITING) {
                if(Util.isConnected(RouterService.this)) {
                    synchronized(_stateLock) {
                        if(_state != State.WAITING) {
                            return;
                        }
                        _statusBar.replace(StatusBar.ICON_STARTING, "Network connected, I2P is starting up");
                        setState(State.STARTING);
                        _starterThread = new Thread(new Starter());
                        _starterThread.start();
                    }
                    return;
                }
                _handler.postDelayed(this, 15 * 1000);
            }
        }
    }

    private class Starter implements Runnable {

        public void run() {
            Util.d(MARKER + this + " starter thread"
                    + " Current state is: " + _state);
            //Util.d(MARKER + this + " JBigI speed test started");
            //NativeBigInteger.main(null);
            //Util.d(MARKER + this + " JBigI speed test finished, launching router");


            // Before we launch, fix up any settings that need to be fixed here.
            // This should be done in the core, but as of this writing it isn't!

            // Step one. Load the propertites.
            Properties props = new OrderedProperties();
            Properties oldprops = new OrderedProperties();
            String wrapName = _myDir + "/router.config";
            try {
                InputStream fin = new FileInputStream(new File(wrapName));
                DataHelper.loadProps(props, fin);
            } catch(IOException ioe) {
                // shouldn't happen...
            }
            oldprops.putAll(props);
            // Step two, check for any port settings, and copy for those that are missing.
            int UDPinbound;
            int UDPinlocal;
            int TCPinbound;
            int TCPinlocal;
            UDPinbound = Integer.parseInt(props.getProperty("i2np.udp.port", "-1"));
            UDPinlocal = Integer.parseInt(props.getProperty("i2np.udp.internalPort", "-1"));
            TCPinbound = Integer.parseInt(props.getProperty("i2np.ntcp.port", "-1"));
            TCPinlocal = Integer.parseInt(props.getProperty("i2np.ntcp.internalPort", "-1"));
            boolean hasUDPinbound = UDPinbound != -1;
            boolean hasUDPinlocal = UDPinlocal != -1;
            boolean hasTCPinbound = TCPinbound != -1;
            boolean hasTCPinlocal = TCPinlocal != -1;

            // check and clear values based on these:
            boolean udp = Boolean.parseBoolean(props.getProperty("i2np.udp.enable", "false"));
            boolean tcp = Boolean.parseBoolean(props.getProperty("i2np.ntcp.enable", "false"));

            // Fix if both are false.
            if(!(udp || tcp)) {
                // If both are not on, turn them both on.
                props.setProperty("i2np.udp.enable", "true");
                props.setProperty("i2np.ntcp.enable", "true");
            }

            // Fix if we have local but no inbound
            if(!hasUDPinbound && hasUDPinlocal) {
                // if we got a local port and no external port, set it
                hasUDPinbound = true;
                UDPinbound = UDPinlocal;
            }
            if(!hasTCPinbound && hasTCPinlocal) {
                // if we got a local port and no external port, set it
                hasTCPinbound = true;
                TCPinbound = TCPinlocal;
            }

            boolean anyUDP = hasUDPinbound || hasUDPinlocal;
            boolean anyTCP = hasTCPinbound || hasTCPinlocal;
            boolean anyport = anyUDP || anyTCP;

            if(!anyport) {
                // generate one for UDPinbound, and fall thru.
                // FIX ME: Possibly not the best but should be OK.
                Random generator = new Random(System.currentTimeMillis());
                UDPinbound = generator.nextInt(55500) + 10000;
                anyUDP = true;
            }

            // Copy missing port numbers
            if(anyUDP && !anyTCP) {
                TCPinbound = UDPinbound;
                TCPinlocal = UDPinlocal;
            }
            if(anyTCP && !anyUDP) {
                UDPinbound = TCPinbound;
                UDPinlocal = TCPinlocal;
            }
            // reset for a retest.
            hasUDPinbound = UDPinbound != -1;
            hasUDPinlocal = UDPinlocal != -1;
            hasTCPinbound = TCPinbound != -1;
            hasTCPinlocal = TCPinlocal != -1;
            anyUDP = hasUDPinbound || hasUDPinlocal;
            anyTCP = hasTCPinbound || hasTCPinlocal;
            boolean checkAnyUDP = anyUDP && udp;
            boolean checkAnyTCP = anyTCP && tcp;

            // Enable things that need to be enabled.
            // Disable anything that needs to be disabled.
            if(!checkAnyUDP && !checkAnyTCP) {
                // enable the one(s) with values.
                if(anyUDP) {
                    udp = true;
                }
                if(anyTCP) {
                    tcp = true;
                }
            }

            if(!udp) {
                props.setProperty("i2np.udp.enable", "false");
                props.remove("i2np.udp.port");
                props.remove("i2np.udp.internalPort");
            } else {
                props.setProperty("i2np.udp.enable", "true");
                if(hasUDPinbound) {
                    props.setProperty("i2np.udp.port", Integer.toString(UDPinbound));
                } else {
                    props.remove("i2np.udp.port");
                }
                if(hasUDPinlocal) {
                    props.setProperty("i2np.udp.internalPort", Integer.toString(UDPinlocal));
                } else {
                    props.remove("i2np.udp.internalPort");
                }
            }

            if(!tcp) {
                props.setProperty("i2np.ntcp.enable", "false");
                props.remove("i2np.ntcp.port");
                props.remove("i2np.ntcp.internalPort");
            } else {
                props.setProperty("i2np.ntcp.enable", "true");
                if(hasTCPinbound) {
                    props.setProperty("i2np.ntcp.port", Integer.toString(TCPinbound));
                } else {
                    props.remove("i2np.ntcp.port");
                }
                if(hasTCPinlocal) {
                    props.setProperty("i2np.ntcp.internalPort", Integer.toString(TCPinlocal));
                } else {
                    props.remove("i2np.ntcp.internalPort");
                }
            }
            // WHEW! Now test for any changes.
            if(!props.equals(oldprops)) {
                // save fixed properties.
                try {
                    DataHelper.storeProps(props, new File(wrapName));
                } catch(IOException ioe) {
                    // shouldn't happen...
                }
            }

            // _NOW_ launch the router!
            RouterLaunch.main(null);
            synchronized(_stateLock) {
                if(_state != State.STARTING) {
                    return;
                }
                setState(State.RUNNING);
                List<?> contexts = RouterContext.listContexts();
                if((contexts == null) || (contexts.isEmpty())) {
                    throw new IllegalStateException("No contexts. This is usually because the router is either starting up or shutting down.");
                }
                _statusBar.replace(StatusBar.ICON_RUNNING, "I2P is running");
                _context = (RouterContext) contexts.get(0);
                _context.router().setKillVMOnEnd(false);
                Job loadJob = new LoadClientsJob(_context, _notif);
                _context.jobQueue().addJob(loadJob);
                _context.addShutdownTask(new ShutdownHook());
                _context.addFinalShutdownTask(new FinalShutdownHook());
                _starterThread = null;
            }
            Util.d("Router.main finished");
        }
    }

    private class Updater implements Runnable {

        public void run() {
            RouterContext ctx = _context;
            if(ctx != null && (_state == State.RUNNING || _state == State.ACTIVE)) {
                Router router = ctx.router();
                if(router.isAlive()) {
                    updateStatus(ctx);
                }
            }
            _handler.postDelayed(this, 15 * 1000);
        }
    }
    private String _currTitle;
    private boolean _hadTunnels;

    private void updateStatus(RouterContext ctx) {
        int active = ctx.commSystem().countActivePeers();
        int known = Math.max(ctx.netDb().getKnownRouters() - 1, 0);
        int inEx = ctx.tunnelManager().getFreeTunnelCount();
        int outEx = ctx.tunnelManager().getOutboundTunnelCount();
        int inCl = ctx.tunnelManager().getInboundClientTunnelCount();
        int outCl = ctx.tunnelManager().getOutboundClientTunnelCount();
        String uptime = DataHelper.formatDuration(ctx.router().getUptime());
        double inBW = ctx.bandwidthLimiter().getReceiveBps() / 1024;
        double outBW = ctx.bandwidthLimiter().getSendBps() / 1024;
        // control total width
        DecimalFormat fmt;
        if(inBW >= 1000 || outBW >= 1000) {
            fmt = new DecimalFormat("#0");
        } else if(inBW >= 100 || outBW >= 100) {
            fmt = new DecimalFormat("#0.0");
        } else {
            fmt = new DecimalFormat("#0.00");
        }

        String text =
                getResources().getString(R.string.notification_status_bw,
                        fmt.format(inBW), fmt.format(outBW)); 

        String bigText =
                getResources().getString(R.string.notification_status_bw,
                        fmt.format(inBW), fmt.format(outBW)) + '\n'
                + getResources().getString(R.string.notification_status_peers,
                        active, known) + '\n'
                + getResources().getString(R.string.notification_status_expl,
                        inEx, outEx) + '\n'
                + getResources().getString(R.string.notification_status_client,
                        inCl, outCl);

        boolean haveTunnels = inCl > 0 && outCl > 0;
        if(haveTunnels != _hadTunnels) {
            if(haveTunnels) {
                _currTitle = "Client tunnels are ready";
                setState(State.ACTIVE);
                _statusBar.replace(StatusBar.ICON_ACTIVE, _currTitle);
            } else {
                _currTitle = "Client tunnels are down";
                setState(State.RUNNING);
                _statusBar.replace(StatusBar.ICON_RUNNING, _currTitle);
            }
            _hadTunnels = haveTunnels;
        } else if (_currTitle == null || _currTitle.equals(""))
            _currTitle = "I2P is running";
        _statusBar.update(_currTitle, text, bigText);
    }

    @Override
    public IBinder onBind(Intent intent) {
        Util.d(this + "onBind called"
                + " Current state is: " + _state);
        Util.d("Intent action: " + intent.getAction());
        // Select the interface to return.
        if (RouterBinder.class.getName().equals(intent.getAction())) {
            // Local Activity wanting access to the RouterContext
            Util.d("Returning RouterContext binder");
            return _binder;
        }
        if (IRouterState.class.getName().equals(intent.getAction())) {
            // Someone wants to monitor the router state.
            Util.d("Returning state binder");
            return mStatusBinder;
        }
        Util.d("Unknown binder request, returning null");
        return null;
    }

    /**
     * IRouterState is defined through IDL
     */
    private final IRouterState.Stub mStatusBinder = new IRouterState.Stub() {

        public void registerCallback(IRouterStateCallback cb)
                throws RemoteException {
            if (cb != null) mStateCallbacks.register(cb);
        }

        public void unregisterCallback(IRouterStateCallback cb)
                throws RemoteException {
            if (cb != null) mStateCallbacks.unregister(cb);
        }

        public boolean isStarted() throws RemoteException {
            return mStartCalled;
        }

        public State getState() throws RemoteException {
            return _state;
        }
    };

    @Override
    public boolean onUnbind(Intent intent) {
        return super.onUnbind(intent);
    }

    // ******** following methods may be accessed from Activities and Receivers ************
    /**
     * @returns null if router is not running
     */
    public RouterContext getRouterContext() {
        RouterContext rv = _context;
        if(rv == null) {
            return null;
        }
        if(!rv.router().isAlive()) {
            return null;
        }
        if(_state != State.RUNNING
                && _state != State.ACTIVE
                && _state != State.STOPPING
                && _state != State.MANUAL_STOPPING
                && _state != State.MANUAL_QUITTING
                && _state != State.NETWORK_STOPPING) {
            return null;
        }
        return rv;
    }

    /**
     * debug
     */
    public String getState() {
        return _state.toString();
    }

    public boolean canManualStop() {
        return _state == State.WAITING || _state == State.STARTING || _state == State.RUNNING || _state == State.ACTIVE;
    }

    /**
     * Stop and don't restart the router, but keep the service
     */
    public void manualStop() {
        Util.d("manualStop called"
                + " Current state is: " + _state);
        synchronized(_stateLock) {
            if(!canManualStop()) {
                return;
            }
            if(_state == State.STARTING) {
                _starterThread.interrupt();
            }
            if(_state == State.STARTING || _state == State.RUNNING || _state == State.ACTIVE) {
                _statusBar.replace(StatusBar.ICON_STOPPING, "Stopping I2P");
                Thread stopperThread = new Thread(new Stopper(State.MANUAL_STOPPING, State.MANUAL_STOPPED));
                stopperThread.start();
            }
        }
    }

    /**
     * Stop the router and kill the service
     */
    public void manualQuit() {
        Util.d("manualQuit called"
                + " Current state is: " + _state);
        synchronized(_stateLock) {
            if(!canManualStop()) {
                return;
            }
            if(_state == State.STARTING) {
                _starterThread.interrupt();
            }
            if(_state == State.STARTING || _state == State.RUNNING || _state == State.ACTIVE) {
                _statusBar.replace(StatusBar.ICON_STOPPING, "Stopping I2P");
                Thread stopperThread = new Thread(new Stopper(State.MANUAL_QUITTING, State.MANUAL_QUITTED));
                stopperThread.start();
            } else if(_state == State.WAITING) {
                setState(State.MANUAL_QUITTING);
                (new FinalShutdownHook()).run();
            }
        }
    }

    /**
     * Stop and then spin waiting for a network connection, then restart
     */
    public void networkStop() {
        Util.d("networkStop called"
                + " Current state is: " + _state);
        synchronized(_stateLock) {
            if(_state == State.STARTING) {
                _starterThread.interrupt();
            }
            if(_state == State.STARTING || _state == State.RUNNING || _state == State.ACTIVE) {
                _statusBar.replace(StatusBar.ICON_STOPPING, "Network disconnected, stopping I2P");
                // don't change state, let the shutdown hook do it
                Thread stopperThread = new Thread(new Stopper(State.NETWORK_STOPPING, State.NETWORK_STOPPING));
                stopperThread.start();
            }
        }
    }

    public boolean canManualStart() {
        // We can be in INIT if we restarted after crash but previous state was not RUNNING.
        return _state == State.INIT || _state == State.MANUAL_STOPPED || _state == State.STOPPED;
    }

    public void manualStart() {
        Util.d("restart called"
                + " Current state is: " + _state);
        synchronized(_stateLock) {
            if(!canManualStart()) {
                return;
            }
            _statusBar.replace(StatusBar.ICON_STARTING, "I2P is starting up");
            setState(State.STARTING);
            _starterThread = new Thread(new Starter());
            _starterThread.start();
        }
    }

    // ******** end methods accessed from Activities and Receivers ************

    private static final int STATE_MSG = 1;

    /**
     * Our Handler used to execute operations on the main thread.
     */
    private final Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
            case STATE_MSG:
                final State state = _state;
                // Broadcast to all clients the new state.
                final int N = mStateCallbacks.beginBroadcast();
                for (int i = 0; i < N; i++) {
                    try {
                        mStateCallbacks.getBroadcastItem(i).stateChanged(state);
                    } catch (RemoteException e) {
                        // The RemoteCallbackList will take care of removing
                        // the dead object for us.
                    }
                }
                mStateCallbacks.finishBroadcast();
                break;
            default:
                super.handleMessage(msg);
            }
        }
    };

    /**
     * Turn off the status bar. Unregister the receiver. If we were running,
     * fire up the Stopper thread.
     */
    @Override
    public void onDestroy() {
        Util.d("onDestroy called"
                + " Current state is: " + _state);

        _handler.removeCallbacks(_updater);
        _statusBar.remove();

        I2PReceiver rcvr = _receiver;
        if(rcvr != null) {
            synchronized(rcvr) {
                try {
                    // throws if not registered
                    unregisterReceiver(rcvr);
                } catch(IllegalArgumentException iae) {
                }
                //rcvr.unbindRouter();
                //_receiver = null;
            }
        }
        synchronized(_stateLock) {
            if(_state == State.STARTING) {
                _starterThread.interrupt();
            }
            if(_state == State.STARTING || _state == State.RUNNING || _state == State.ACTIVE) {
                // should this be in a thread?
                _statusBar.replace(StatusBar.ICON_SHUTTING_DOWN, "I2P is shutting down");
                Thread stopperThread = new Thread(new Stopper(State.STOPPING, State.STOPPED));
                stopperThread.start();
            }
        }
    }

    /**
     * Transition to the next state. If we still have a context, shut down the
     * router. Turn off the status bar. Then transition to the stop state.
     */
    private class Stopper implements Runnable {

        private final State nextState;
        private final State stopState;

        /**
         * call holding statelock
         */
        public Stopper(State next, State stop) {
            nextState = next;
            stopState = stop;
            setState(next);
        }

        public void run() {
            try {
                Util.d(MARKER + this + " stopper thread"
                        + " Current state is: " + _state);
                RouterContext ctx = _context;
                if(ctx != null) {
                    ctx.router().shutdown(Router.EXIT_HARD);
                }
                _statusBar.remove();
                Util.d("********** Router shutdown complete");
                synchronized(_stateLock) {
                    if(_state == nextState) {
                        setState(stopState);
                    }
                }
            } finally {
                stopForeground(true);
                _statusBar.remove();
            }
        }
    }

    /**
     * First (early) hook. Update the status bar. Unregister the receiver.
     */
    private class ShutdownHook implements Runnable {

        public void run() {
            Util.d(this + " shutdown hook"
                    + " Current state is: " + _state);
            _statusBar.replace(StatusBar.ICON_SHUTTING_DOWN, "I2P is shutting down");
            I2PReceiver rcvr = _receiver;
            if(rcvr != null) {
                synchronized(rcvr) {
                    try {
                        // throws if not registered
                        unregisterReceiver(rcvr);
                    } catch(IllegalArgumentException iae) {
                    }
                    //rcvr.unbindRouter();
                    //_receiver = null;
                }
            }
            synchronized(_stateLock) {
                // null out to release the memory
                _context = null;
                if(_state == State.STARTING) {
                    _starterThread.interrupt();
                }
                if(_state == State.WAITING || _state == State.STARTING
                        || _state == State.RUNNING || _state == State.ACTIVE) {
                    setState(State.STOPPING);
                }
            }
        }
    }

    /**
     * Second (late) hook. Turn off the status bar. Null out the context. If we
     * were stopped manually, do nothing. If we were stopped because of no
     * network, start the waiter thread. If it stopped of unknown causes or from
     * manualQuit(), kill the Service.
     */
    private class FinalShutdownHook implements Runnable {

        public void run() {
            try {
                Util.d(this + " final shutdown hook"
                        + " Current state is: " + _state);
                //I2PReceiver rcvr = _receiver;

                synchronized(_stateLock) {
                    // null out to release the memory
                    _context = null;
                    Runtime.getRuntime().gc();
                    if(_state == State.STARTING) {
                        _starterThread.interrupt();
                    }
                    if(_state == State.MANUAL_STOPPING) {
                        setState(State.MANUAL_STOPPED);
                    } else if(_state == State.NETWORK_STOPPING) {
                        // start waiter handler
                        setState(State.WAITING);
                        _handler.postDelayed(new Waiter(), 10 * 1000);
                    } else if(_state == State.STARTING || _state == State.RUNNING
                            || _state == State.ACTIVE || _state == State.STOPPING) {
                        Util.w(this + " died of unknown causes");
                        setState(State.STOPPED);
                        // Unregister all callbacks.
                        mStateCallbacks.kill();
                        stopForeground(true);
                        stopSelf();
                    } else if(_state == State.MANUAL_QUITTING) {
                        setState(State.MANUAL_QUITTED);
                        // Unregister all callbacks.
                        mStateCallbacks.kill();
                        stopForeground(true);
                        stopSelf();
                    }
                }
            } finally {
                _statusBar.remove();
            }
        }
    }

    private State getSavedState() {
        SharedPreferences prefs = getSharedPreferences(SHARED_PREFS, 0);
        String stateString = prefs.getString(LAST_STATE, State.INIT.toString());
        try {
            return State.valueOf(stateString);
        } catch (IllegalArgumentException e) {
            return State.INIT;
        }
    }

    private void setState(State s) {
        _state = s;
        saveState();
        mHandler.sendEmptyMessage(STATE_MSG);
    }

    /**
     * @return success
     */
    private boolean saveState() {
        SharedPreferences prefs = getSharedPreferences(SHARED_PREFS, 0);
        SharedPreferences.Editor edit = prefs.edit();
        edit.putString(LAST_STATE, _state.toString());
        return edit.commit();
    }
}