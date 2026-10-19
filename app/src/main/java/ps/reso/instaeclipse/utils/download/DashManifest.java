/*
 * Based on HushGram DashManifest, Copyright 2026 Andrew Liang (GPL-3.0),
 * Hushfacebook and HushGram contributors. See third-party/HushGram/NOTICE.md.
 * Modified for InstaEclipse: bounded XML, explicit unsupported results, dimensions-only quality.
 */
package ps.reso.instaeclipse.utils.download;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.helpers.DefaultHandler;

/** Only static, single-period, single whole-file BaseURL representations are supported. */
final class DashManifest {
    enum State { ABSENT, SUPPORTED, UNSUPPORTED, ENCRYPTED, INVALID, TOO_LARGE }
    static final class Track {
        final String mime, codecs, url, id;
        final int width, height;
        final long bandwidth;
        final double frameRate;
        Track(String mime, String codecs, String url, String id, int width, int height, long bandwidth, double fps) {
            this.mime = mime; this.codecs = codecs; this.url = url; this.id = id;
            this.width = width; this.height = height; this.bandwidth = bandwidth; this.frameRate = fps;
        }
        boolean video() { return mime.startsWith("video/"); }
        boolean audio() { return mime.startsWith("audio/"); }
    }
    final State state;
    final List<Track> tracks;
    final String reason;
    private DashManifest(State state, List<Track> tracks, String reason) {
        this.state = state; this.tracks = Collections.unmodifiableList(tracks); this.reason = reason;
    }
    private static DashManifest fail(State state, String reason) {
        return new DashManifest(state, Collections.emptyList(), reason);
    }
    static DashManifest parse(String xml) {
        if (xml == null || xml.trim().isEmpty()) return fail(State.ABSENT, "No DASH manifest was exposed for this item");
        if (xml.length() > DownloadRequest.MAX_MANIFEST_CHARS) return fail(State.TOO_LARGE, "DASH manifest exceeds parsing limit");
        String upper = xml.toUpperCase(Locale.ROOT);
        if (upper.contains("<!DOCTYPE") || upper.contains("<!ENTITY")) return fail(State.UNSUPPORTED, "DASH declarations are unsupported");
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            f.setExpandEntityReferences(false);
            // Android's XML implementation lacks some JAXP features. DOCTYPE/entity rejection
            // above is unconditional; this hardening is additional on implementations supporting it.
            try { f.setFeature("http://xml.org/sax/features/external-general-entities", false); } catch (Exception ignored) { }
            try { f.setFeature("http://xml.org/sax/features/external-parameter-entities", false); } catch (Exception ignored) { }
            javax.xml.parsers.DocumentBuilder builder = f.newDocumentBuilder();
            builder.setEntityResolver((publicId, systemId) -> new InputSource(new StringReader("")));
            builder.setErrorHandler(new DefaultHandler());
            Document document = builder.parse(new InputSource(new StringReader(xml)));
            Element root = document.getDocumentElement();
            if (!name(root).equals("MPD")) return fail(State.INVALID, "Expected a DASH MPD");
            if (!bounded(root, 0, new int[1])) return fail(State.TOO_LARGE, "DASH structure exceeds parsing limit");
            if (root.hasAttribute("type") && !root.getAttribute("type").equals("static"))
                return fail(State.UNSUPPORTED, "Only static DASH manifests are supported");
            if (all(root, "ContentProtection").getLength() > 0) return fail(State.ENCRYPTED, "Encrypted DASH tracks are unsupported");
            if (all(root, "SegmentTemplate").getLength() > 0 || all(root, "SegmentList").getLength() > 0)
                return fail(State.UNSUPPORTED, "Segmented DASH templates/lists are unsupported");
            NodeList initializations = all(root, "Initialization");
            for (int i = 0; i < initializations.getLength(); i++) {
                if (((Element) initializations.item(i)).hasAttribute("sourceURL"))
                    return fail(State.UNSUPPORTED, "Separate DASH initialization files are unsupported");
            }
            List<Element> periods = children(root, "Period");
            if (periods.size() != 1) return fail(State.UNSUPPORTED, "DASH requires one period");
            if (!children(root, "BaseURL").isEmpty() || !children(periods.get(0), "BaseURL").isEmpty())
                return fail(State.UNSUPPORTED, "Inherited DASH addresses are unsupported");
            List<Element> sets = children(periods.get(0), "AdaptationSet");
            if (sets.isEmpty() || sets.size() > 16) return fail(State.TOO_LARGE, "DASH adaptation set count is unsupported");
            List<Track> tracks = new ArrayList<>();
            int audioSets = 0;
            for (Element set : sets) {
                if (!children(set, "BaseURL").isEmpty()) return fail(State.UNSUPPORTED, "Inherited DASH addresses are unsupported");
                List<Element> representations = children(set, "Representation");
                if (representations.isEmpty()) return fail(State.UNSUPPORTED, "DASH adaptation set has no representations");
                String setMime = null;
                for (Element rep : representations) {
                    if (tracks.size() >= 64) return fail(State.TOO_LARGE, "DASH track count exceeds limit");
                    List<Element> urls = children(rep, "BaseURL");
                    if (urls.size() != 1) return fail(State.UNSUPPORTED, "DASH track requires one whole-file address");
                    String url = urls.get(0).getTextContent().trim();
                    if (url.length() > 4096 || MediaUrlPolicy.shapeRefusal(url) != null)
                        return fail(State.UNSUPPORTED, "DASH track address is not an allowed HTTPS media address");
                    String mime = attribute(rep, set, "mimeType").toLowerCase(Locale.ROOT);
                    if (mime.isEmpty()) mime = attribute(rep, set, "contentType").toLowerCase(Locale.ROOT) + "/mp4";
                    if (!mime.equals("video/mp4") && !mime.equals("audio/mp4"))
                        return fail(State.UNSUPPORTED, "DASH container is unsupported");
                    if (setMime != null && !setMime.equals(mime))
                        return fail(State.UNSUPPORTED, "Mixed audio/video DASH adaptation sets are unsupported");
                    setMime = mime;
                    tracks.add(new Track(mime, attribute(rep, set, "codecs").toLowerCase(Locale.ROOT), url,
                            rep.getAttribute("id"), dimension(attribute(rep, set, "width")),
                            dimension(attribute(rep, set, "height")), number(rep.getAttribute("bandwidth")),
                            fps(attribute(rep, set, "frameRate"))));
                }
                if ("audio/mp4".equals(setMime) && ++audioSets > 1)
                    return fail(State.UNSUPPORTED, "Multiple alternative DASH audio groups require an explicit audio choice");
            }
            if (tracks.stream().noneMatch(Track::video)) return fail(State.UNSUPPORTED, "DASH has no video representations");
            return new DashManifest(State.SUPPORTED, tracks, "Exposed whole-file DASH tracks");
        } catch (Exception e) {
            return fail(State.INVALID, "DASH manifest could not be parsed");
        }
    }
    private static String name(Node node) { return node.getLocalName() == null ? node.getNodeName() : node.getLocalName(); }
    private static NodeList all(Element e, String name) { return e.getElementsByTagNameNS("*", name); }
    private static List<Element> children(Element parent, String tag) {
        List<Element> result = new ArrayList<>();
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling())
            if (n instanceof Element && name(n).equals(tag)) result.add((Element) n);
        return result;
    }
    private static boolean bounded(Node n, int depth, int[] count) {
        if (depth > 16 || ++count[0] > 4096) return false;
        for (Node c = n.getFirstChild(); c != null; c = c.getNextSibling()) if (!bounded(c, depth + 1, count)) return false;
        return true;
    }
    private static String attribute(Element rep, Element set, String key) {
        return rep.hasAttribute(key) ? rep.getAttribute(key) : set.getAttribute(key);
    }
    private static long number(String text) {
        try { return Math.max(0, Long.parseLong(text)); } catch (RuntimeException e) { return 0; }
    }
    private static int dimension(String s) { long n = number(s); return n <= 32768 ? (int) n : 0; }
    private static double fps(String s) {
        try {
            String[] parts = s.split("/", -1);
            double n = parts.length == 1 ? Double.parseDouble(s) : parts.length == 2
                    ? Double.parseDouble(parts[0]) / Double.parseDouble(parts[1]) : 0;
            return Double.isFinite(n) && n > 0 && n <= 1000 ? n : 0;
        } catch (RuntimeException e) { return 0; }
    }
}
