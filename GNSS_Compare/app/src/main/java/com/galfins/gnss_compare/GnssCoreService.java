package com.galfins.gnss_compare;

import android.app.Service;
import android.content.Intent;
import android.location.LocationManager;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.support.annotation.Nullable;
import android.util.Log;

import com.galfins.gnss_compare.Constellations.Constellation;
import com.galfins.gnss_compare.Constellations.GalileoConstellation;
import com.galfins.gnss_compare.Constellations.GalileoGpsConstellation;
import com.galfins.gnss_compare.Constellations.GpsConstellation;
import com.galfins.gnss_compare.Corrections.Correction;
import com.galfins.gnss_compare.Corrections.ShapiroCorrection;
import com.galfins.gnss_compare.Corrections.TropoCorrection;
import com.galfins.gnss_compare.FileLoggers.FileLogger;
import com.galfins.gnss_compare.FileLoggers.NmeaFileLogger;
import com.galfins.gnss_compare.PvtMethods.DynamicExtendedKalmanFilter;
import com.galfins.gnss_compare.PvtMethods.PvtMethod;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;

import java.util.ArrayList;
import java.util.List;
import java.util.Observable;
import java.util.Observer;

/**
 * Created by Mateusz on 9/6/2018.
 * This class is for:
 */
public class GnssCoreService extends Service {

    private static final List<DeviceModel> SUPPORTED_DUAL_FREQUENCY_DEVICES = new ArrayList<DeviceModel>(){{
        add(new DeviceModel("Xiaomi", "MI 8"));
    }};

    boolean dualFrequencySupported = false;

    private static final class DeviceModel {
        private String manufacturer;
        private String model;

        DeviceModel(String manufacturer, String model){
            this.manufacturer = manufacturer;
            this.model = model;
        }

        @Override
        public boolean equals(Object obj) {
            if (getClass() != obj.getClass())
                return false;

            DeviceModel compared = (DeviceModel) obj;
            return compared.model.equals(model) && compared.manufacturer.equals(manufacturer);
        }
    }

    /**
     * Tag used to mark module names for savedInstanceStates of the onCreate method.
     */
    private final String MODULE_NAMES_BUNDLE_TAG = "__module_names";

    private static Bundle savedModulesBundle = null;

    CalculationModulesArrayList calculationModules = new CalculationModulesArrayList();

    Observable gnssCoreObservable = new Observable(){
        @Override
        public void notifyObservers() {
            setChanged();
            super.notifyObservers(calculationModules); // by default passing the calculation modules
        }
    };

    CalculationModulesArrayList.PoseUpdatedListener poseListener = new CalculationModulesArrayList.PoseUpdatedListener(){
        @Override
        public void onPoseUpdated() {
            gnssCoreObservable.notifyObservers();
        }
    };

    private final String TAG = this.getClass().getSimpleName();

    public class GnssCoreBinder extends Binder{

        public void addObserver(Observer observer){
            gnssCoreObservable.addObserver(observer);
        }

        public void removeObserver(Observer observer){
            gnssCoreObservable.deleteObserver(observer);
        }

        public CalculationModulesArrayList getCalculationModules(){
            return calculationModules;
        }

        public void addModule(CalculationModule newModule){
            calculationModules.add(newModule);
        }

        public void removeModule(CalculationModule removedModule){
            calculationModules.remove(removedModule);
        }

        public boolean getServiceStarted(){
            return serviceStarted;
        }
    }

    private IBinder binder = new GnssCoreBinder();

    private static boolean serviceStarted = false;

    public static boolean isServiceStarted(){
        return serviceStarted;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onCreate() {
        super.onCreate();

        DeviceModel thisDevice = new DeviceModel(Build.MANUFACTURER, Build.MODEL);

        for(DeviceModel device : SUPPORTED_DUAL_FREQUENCY_DEVICES)
            if(thisDevice.equals(device))
                dualFrequencySupported = true;

        Constellation.initialize(dualFrequencySupported);
        Correction.initialize();
        PvtMethod.initialize();
        FileLogger.initialize();

        if(calculationModules.size() == 0){
            if(savedModulesBundle==null)
                createInitialCalculationModules();
            else
                createCalculationModulesFromBundle();

            FusedLocationProviderClient mFusedLocationClient = LocationServices.getFusedLocationProviderClient(this);
            LocationManager mLocationManager = (LocationManager) getApplicationContext().getSystemService(LOCATION_SERVICE);

            calculationModules.registerForGnssUpdates(mFusedLocationClient, mLocationManager);
            calculationModules.assignPoseUpdatedListener(poseListener);

        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {

        serviceStarted = true;

        return super.onStartCommand(intent, flags, startId);
    }

    /**
     * Waits for length*0.1s for the service to start
     * @param length duration in length*0.1s
     * @return true if service has started in defined time, false otherwise
     */
    public static boolean waitForServiceStarted(int length){
        for(int i=0; i<length; i++) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            if(isServiceStarted())
                return true;
        }
        return false;
    }

    /**
     * Waits for service to start for 15s.
     * @return true if service has started within 15s, false otherwise
     */
    public static boolean waitForServiceStarted(){
        return waitForServiceStarted(150);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        saveModulesDescriptionToBundle();

        calculationModules.unregisterFromGnssUpdates();
        calculationModules.clear();
        CalculationModule.clear();

        serviceStarted = false;

        stopSelf();
    }

    /**
     * Saves the descriptions of calculation modules to a bundle object
     * todo: move to calcualtionModulesArrayList?
     */
    private void saveModulesDescriptionToBundle() {

        ArrayList<String> modulesNames = new ArrayList<>();
        savedModulesBundle = new Bundle();

        for (CalculationModule module: calculationModules)
            modulesNames.add(module.getName());

        savedModulesBundle.putStringArrayList(MODULE_NAMES_BUNDLE_TAG, modulesNames);

        for (CalculationModule module : calculationModules){
            Bundle moduleDescription = module.getConstructorBundle();
            savedModulesBundle.putBundle(module.getName(), moduleDescription);
        }
    }

    /**
     * Creates new calculation modules, based on data stored in the bundle
     * todo: move to calcualtionModulesArrayList?
     */
    private void createCalculationModulesFromBundle() {

        if(savedModulesBundle!=null) {

            ArrayList<String> modulesNames = savedModulesBundle.getStringArrayList(MODULE_NAMES_BUNDLE_TAG);

            if (modulesNames != null) {
                for (String name : modulesNames) {
                    try {
                        Bundle constructorBundle = savedModulesBundle.getBundle(name);
                        if (constructorBundle != null)
                            calculationModules.add(CalculationModule.fromConstructorBundle(constructorBundle));
                    } catch (CalculationModule.NameAlreadyRegisteredException | CalculationModule.NumberOfSeriesExceededLimitException e) {
                        e.printStackTrace();
                    }
                }
            }
            savedModulesBundle = null;

        }
    }

    private void createInitialCalculationModules(){
        final List<CalculationModule> initialModules = new ArrayList<>();

        try {
            initialModules.add(new CalculationModule(
                    "Galileo+GPS",
                    GalileoGpsConstellation.class,
                    new ArrayList<Class<? extends Correction>>() {{
                        add(ShapiroCorrection.class);
                        add(TropoCorrection.class);
                    }},
                    DynamicExtendedKalmanFilter.class,
                    NmeaFileLogger.class));

            initialModules.add(new CalculationModule(
                    "GPS",
                    GpsConstellation.class,
                    new ArrayList<Class<? extends Correction>>() {{
                        add(ShapiroCorrection.class);
                        add(TropoCorrection.class);
                    }},
                    DynamicExtendedKalmanFilter.class,
                    NmeaFileLogger.class));

            initialModules.add(new CalculationModule(
                    "Galileo",
                    GalileoConstellation.class,
                    new ArrayList<Class<? extends Correction>>() {{
                        add(ShapiroCorrection.class);
                        add(TropoCorrection.class);
                    }},
                    DynamicExtendedKalmanFilter.class,
                    NmeaFileLogger.class));
        } catch (Exception e){
            e.printStackTrace();
            Log.e(TAG, "createInitialCalculationModules: Exception when creating modules");
        }

        for(CalculationModule calculationModule : initialModules) {
            calculationModules.add(calculationModule); // when simplified to addAll, doesn't work properly
        }
    }
}