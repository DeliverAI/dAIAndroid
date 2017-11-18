package com.google.android.gms.samples.vision.face.facetracker.models;

/**
 * Created by jonahchin on 2017-11-18.
 */

public class User {
    private String name;
    private String phoneNumber;
    private String address;

    public User(String name, String phoneNumber, String address){
        this.name = name;
        this.phoneNumber = phoneNumber;
        this.address = address;
    }

    public String getName() {
        return name;
    }

    public String getPhoneNumber() {
        return phoneNumber;
    }

    public String getAddress() {
        return address;
    }
}
