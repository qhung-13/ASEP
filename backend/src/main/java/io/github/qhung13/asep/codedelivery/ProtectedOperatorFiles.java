package io.github.qhung13.asep.codedelivery;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFileAttributes;

/** Linux/POSIX trusted host files. A trusted caller establishes the anchor and expected owner.
 * No filename, UID or permission claim from a delivery request establishes that trust.
 */
final class ProtectedOperatorFiles {
    private ProtectedOperatorFiles() {}
    static byte[] read(Path anchor, Path path, long owner, int maximumBytes, boolean secret) {
        try {
            anchor = anchor.toAbsolutePath().normalize(); path = path.toAbsolutePath().normalize();
            if (!path.startsWith(anchor) || path.equals(anchor)) throw denied();
            Path current = anchor; directory(current, owner, secret);
            Path relative = anchor.relativize(path);
            for (int n=0;n<relative.getNameCount()-1;n++) { current=current.resolve(relative.getName(n)); directory(current, owner, secret); }
            var attributes = Files.readAttributes(path, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile() || !owner(path, owner) || unsafe(path, secret)
                    || attributes.size()>maximumBytes || ((Number)Files.getAttribute(path,"unix:nlink",LinkOption.NOFOLLOW_LINKS)).intValue()!=1) throw denied();
            try (var channel=FileChannel.open(path,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)) {
                var buffer=ByteBuffer.allocate(maximumBytes+1);
                while (buffer.hasRemaining() && channel.read(buffer)>=0) { /* bounded regular file */ }
                if (buffer.position()>maximumBytes) throw denied();
                return java.util.Arrays.copyOf(buffer.array(),buffer.position());
            }
        } catch (IOException | RuntimeException invalid) { throw denied(); }
    }
    static void directory(Path path,long owner,boolean secret) throws IOException {
        var attrs=Files.readAttributes(path,PosixFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        if (!attrs.isDirectory() || !owner(path,owner) || unsafe(path,secret)) throw denied();
    }
    static void hierarchy(Path path,long owner) {
        try {
            for (Path current=path.toAbsolutePath().normalize(); current!=null;current=current.getParent()) directory(current,owner,false);
        } catch (IOException | RuntimeException invalid) { throw denied(); }
    }
    private static boolean owner(Path path,long expected) throws IOException {
        long uid=((Number)Files.getAttribute(path,"unix:uid",LinkOption.NOFOLLOW_LINKS)).longValue();
        return uid==expected || uid==0;
    }
    private static boolean unsafe(Path path,boolean secret) throws IOException {
        int mode=((Number)Files.getAttribute(path,"unix:mode",LinkOption.NOFOLLOW_LINKS)).intValue();
        return (mode & (secret ? 0077 : 0022))!=0;
    }
    static IllegalStateException denied() { return new IllegalStateException("Restricted operator file unavailable or unsafe"); }
}
