package com.example.shop;
public class Shop {
    static final Object stock = new Object(), bank = new Object();
    static void pause() { try { Thread.sleep(300); } catch (InterruptedException e) { } }
    public static void main(String[] args) throws Exception {
        Thread sync = new Thread(() -> { synchronized (bank) { pause(); synchronized (stock) { System.out.println("synced"); } } }, "Bank-Sync");
        Thread.currentThread().setName("Server thread");
        sync.start();
        synchronized (stock) { pause(); synchronized (bank) { System.out.println("saved"); } }
    }
}
