package com.feurstagram.extension;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

/**
 * Filters only direct entries of a root timeline array. Keeps surviving posts in
 * their exact order and leaves every cursor, status and nested list untouched.
 * Buffers at most one item (1 MiB); oversized/unrecognized items pass through.
 */
final class FollowingMuteFilter {
    private static final int MAX_ITEM = 1024 * 1024;
    private int depth;
    private boolean started, passthrough, quoted, escaped, keyString, wantKey, wantValue;
    private String field;
    private ByteArrayOutputStream key;
    private boolean array, emitted, active, streaming, itemQuoted, itemEscaped, scalar;
    private int itemDepth;
    private ByteArrayOutputStream item;

    synchronized ByteBuffer filter(ByteBuffer source) {
        ByteBuffer input = source.duplicate();
        ByteArrayOutputStream out = new ByteArrayOutputStream(input.remaining());
        while (input.hasRemaining()) accept(input.get() & 255, out);
        return ByteBuffer.wrap(out.toByteArray());
    }

    private static boolean space(int c) { return c==' ' || c=='\n' || c=='\r' || c=='\t'; }
    private static boolean delivery(String key) {
        return "feed_items".equals(key) || "items".equals(key) || "items_with_ads".equals(key);
    }
    private void accept(int c, ByteArrayOutputStream out) {
        if (passthrough) { out.write(c); return; }
        if (!started && !space(c)) {
            started=true;
            if(c!='{') { passthrough=true; out.write(c); return; }
        }
        if (array) { acceptArray(c,out); return; }
        out.write(c);
        if (quoted) {
            if (keyString && key != null) {
                if (key.size() < 256) key.write(c); else key=null;
            }
            if (escaped) escaped=false;
            else if(c=='\\') escaped=true;
            else if(c=='"') {
                quoted=false;
                if (keyString) { field=key==null?null:FeedAuthor.key(key.toByteArray()); wantKey=false; }
                keyString=false; key=null;
            }
            return;
        }
        if (wantValue && !space(c)) {
            wantValue=false;
            if (depth==1 && c=='[' && delivery(field)) { array=true; emitted=false; field=null; return; }
            field=null;
        }
        if(c=='"') {
            quoted=true; escaped=false; keyString=depth==1 && wantKey;
            if(keyString) { key=new ByteArrayOutputStream(32); key.write(c); }
        } else if(c=='{' || c=='[') {
            depth++; if(depth==1 && c=='{') wantKey=true;
        } else if(c=='}' || c==']') {
            depth--; if(depth==0) { wantKey=false; field=null; }
        } else if(c==':' && depth==1) wantValue=true;
        else if(c==',' && depth==1) { wantKey=true; field=null; }
    }
    private void prefix(ByteArrayOutputStream out) { if(emitted) out.write(','); emitted=true; }
    private void writeItem(int c, ByteArrayOutputStream out) {
        if (streaming) { out.write(c); return; }
        if (item.size() >= MAX_ITEM) {
            prefix(out); byte[] bytes=item.toByteArray(); out.write(bytes,0,bytes.length);
            streaming=true; item=null; out.write(c);
        } else item.write(c);
    }
    private void endItem(ByteArrayOutputStream out) {
        if (!streaming) {
            byte[] bytes=item.toByteArray();
            FeedAuthor author=FeedAuthor.fromItem(bytes);
            if(author==null || !FeedMutes.observeAndHide(author.id,author.username)) {
                prefix(out); out.write(bytes,0,bytes.length);
            }
        }
        active=false; item=null; streaming=false; itemQuoted=false; itemEscaped=false; scalar=false; itemDepth=0;
    }
    private void acceptArray(int c, ByteArrayOutputStream out) {
        if (!active) {
            if(space(c) || c==',') return;
            if(c==']') { array=false; out.write(c); return; }
            active=true; item=new ByteArrayOutputStream(4096);
            itemDepth=0; itemQuoted=false; itemEscaped=false;
            scalar=c!='{' && c!='[' && c!='"';
        } else if(scalar && (space(c)||c==','||c==']')) {
            endItem(out); acceptArray(c,out); return;
        }
        writeItem(c,out);
        if(itemQuoted) {
            if(itemEscaped) itemEscaped=false;
            else if(c=='\\') itemEscaped=true;
            else if(c=='"') { itemQuoted=false; if(itemDepth==0) endItem(out); }
        } else if(c=='"') itemQuoted=true;
        else if(c=='{'||c=='[') itemDepth++;
        else if(c=='}'||c==']') { if(--itemDepth==0) endItem(out); }
    }
}
