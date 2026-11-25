package com.feurstagram.extension;

/**
 * Patch-time adapter to Instagram's verified request callback superclass.
 * The patch supplies the native success/failure overrides, delegating here.
 */
public class NativeReelCallback {
    private final SharedReelLoader owner;
    public NativeReelCallback(SharedReelLoader owner) { this.owner = owner; }
    public void success(Object response) { owner.success(response); }
    public void failure(Object error) { owner.failure(error); }
}
