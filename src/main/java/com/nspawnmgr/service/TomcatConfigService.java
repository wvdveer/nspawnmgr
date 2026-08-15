package com.nspawnmgr.service;

import com.nspawnmgr.cli.TomcatConfigWriter;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Reads and rewrites the Tomcat instance nspawnmgr itself runs in — {@code conf/server.xml}'s
 * plain HTTP connector port, and an optional HTTPS connector (PEM-based, matching the exact shape
 * documented in docs/administrator-guide.md &sect;6 "Enabling HTTPS" — no Java keystore).
 *
 * <p>{@code server.xml} is the source of truth, read fresh on every call — not mirrored into
 * {@code AppSettings}/the database, the same reasoning as the Guacamole structured editor: an
 * admin can and sometimes will hand-edit this file directly (it's what &sect;6 itself documents as
 * the manual procedure), so nspawnmgr showing anything other than what's actually on disk would be
 * actively misleading.
 *
 * <p>Located via the {@code catalina.base} system property Tomcat's own startup script always sets
 * for the whole JVM — robust across the {@code .deb}'s Debian-packaged {@code tomcat9}
 * ({@code /etc/tomcat9}) and a manually-extracted Tomcat ({@code /opt/tomcat9}) alike, without
 * needing to guess which layout is in use.
 */
@Service
public class TomcatConfigService {

    private final TomcatConfigWriter writer;

    public TomcatConfigService(TomcatConfigWriter writer) {
        this.writer = writer;
    }

    public static Path serverXmlPath() {
        String catalinaBase = System.getProperty("catalina.base", System.getProperty("catalina.home"));
        return Path.of(catalinaBase, "conf", "server.xml");
    }

    public TomcatConnectorStatus readStatus() {
        Path path = serverXmlPath();
        String content;
        try {
            content = Files.readString(path);
        } catch (IOException e) {
            return new TomcatConnectorStatus(path.toString(), false, 0, false, null, null, null,
                    "Cannot read " + path + ": " + e.getMessage());
        }
        try {
            Document doc = parse(content);
            Element httpConnector = findHttpConnector(doc);
            if (httpConnector == null) {
                return new TomcatConnectorStatus(path.toString(), true, 0, false, null, null, null,
                        "No plain HTTP <Connector> found in " + path);
            }
            int httpPort = Integer.parseInt(httpConnector.getAttribute("port"));
            Element sslConnector = findSslConnector(doc);
            boolean httpsEnabled = sslConnector != null;
            Integer httpsPort = null;
            String certificateFile = null;
            String certificateKeyFile = null;
            if (httpsEnabled) {
                httpsPort = Integer.parseInt(sslConnector.getAttribute("port"));
                Element certificate = findCertificateElement(sslConnector);
                if (certificate != null) {
                    certificateFile = certificate.getAttribute("certificateFile");
                    certificateKeyFile = certificate.getAttribute("certificateKeyFile");
                }
            }
            return new TomcatConnectorStatus(path.toString(), true, httpPort, httpsEnabled, httpsPort,
                    certificateFile, certificateKeyFile, null);
        } catch (Exception e) {
            return new TomcatConnectorStatus(path.toString(), true, 0, false, null, null, null,
                    "Could not parse " + path + ": " + e.getMessage());
        }
    }

    /** Takes effect only after a Tomcat restart — see the "Restart Tomcat" button on /admin/settings. */
    public void update(int httpPort, boolean httpsEnabled, Integer httpsPort, String certificateFile, String certificateKeyFile) {
        if (httpPort < 1 || httpPort > 65535) {
            throw new IllegalArgumentException("HTTP port must be between 1 and 65535");
        }
        if (httpsEnabled) {
            if (httpsPort == null || httpsPort < 1 || httpsPort > 65535) {
                throw new IllegalArgumentException("HTTPS port must be between 1 and 65535");
            }
            if (certificateFile == null || certificateFile.isBlank()
                    || certificateKeyFile == null || certificateKeyFile.isBlank()) {
                throw new IllegalArgumentException("Certificate file and certificate key file are required to enable HTTPS");
            }
        }
        Path path = serverXmlPath();
        String current;
        try {
            current = Files.readString(path);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + path + ": " + e.getMessage(), e);
        }

        Document doc;
        Element httpConnector;
        try {
            doc = parse(current);
            httpConnector = findHttpConnector(doc);
        } catch (Exception e) {
            throw new IllegalStateException("Could not parse " + path + ": " + e.getMessage(), e);
        }
        if (httpConnector == null) {
            throw new IllegalStateException("Could not find the plain HTTP <Connector> in " + path);
        }
        httpConnector.setAttribute("port", String.valueOf(httpPort));

        Element sslConnector = findSslConnector(doc);
        if (httpsEnabled) {
            if (sslConnector == null) {
                sslConnector = buildSslConnector(doc, httpsPort, certificateFile, certificateKeyFile);
                // Insert right after the HTTP connector, NOT appendChild() on <Service> — that
                // would land after </Engine>, which Tomcat's server.xml schema/digester rejects
                // (Connectors must precede Engine within Service).
                httpConnector.getParentNode().insertBefore(sslConnector, httpConnector.getNextSibling());
            } else {
                sslConnector.setAttribute("port", String.valueOf(httpsPort));
                Element certificate = findCertificateElement(sslConnector);
                if (certificate != null) {
                    certificate.setAttribute("certificateFile", certificateFile);
                    certificate.setAttribute("certificateKeyFile", certificateKeyFile);
                } else {
                    sslConnector.appendChild(buildSslHostConfig(doc, certificateFile, certificateKeyFile));
                }
            }
        } else if (sslConnector != null) {
            sslConnector.getParentNode().removeChild(sslConnector);
        }

        String updated;
        try {
            updated = serialize(doc);
        } catch (Exception e) {
            throw new IllegalStateException("Could not serialize updated " + path + ": " + e.getMessage(), e);
        }
        writer.write(path.toString(), updated);
    }

    private static Document parse(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        DocumentBuilder builder = factory.newDocumentBuilder();
        return builder.parse(new InputSource(new StringReader(xml)));
    }

    private static String serialize(Document doc) throws Exception {
        Transformer transformer = TransformerFactory.newInstance().newTransformer();
        transformer.setOutputProperty(OutputKeys.INDENT, "yes");
        transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
        StringWriter out = new StringWriter();
        transformer.transform(new DOMSource(doc), new StreamResult(out));
        return out.toString();
    }

    /** The plain HTTP connector: not SSL-enabled, and not the AJP connector. */
    private static Element findHttpConnector(Document doc) {
        for (Element connector : connectors(doc)) {
            String protocol = connector.getAttribute("protocol");
            boolean isAjp = protocol.toUpperCase().contains("AJP");
            boolean isSsl = "true".equalsIgnoreCase(connector.getAttribute("SSLEnabled"));
            if (!isAjp && !isSsl) {
                return connector;
            }
        }
        return null;
    }

    private static Element findSslConnector(Document doc) {
        for (Element connector : connectors(doc)) {
            if ("true".equalsIgnoreCase(connector.getAttribute("SSLEnabled"))) {
                return connector;
            }
        }
        return null;
    }

    private static Element findCertificateElement(Element sslConnector) {
        NodeList sslHostConfigs = sslConnector.getElementsByTagName("SSLHostConfig");
        if (sslHostConfigs.getLength() == 0) {
            return null;
        }
        NodeList certificates = ((Element) sslHostConfigs.item(0)).getElementsByTagName("Certificate");
        return certificates.getLength() == 0 ? null : (Element) certificates.item(0);
    }

    private static java.util.List<Element> connectors(Document doc) {
        java.util.List<Element> result = new java.util.ArrayList<>();
        NodeList nodes = doc.getElementsByTagName("Connector");
        for (int i = 0; i < nodes.getLength(); i++) {
            result.add((Element) nodes.item(i));
        }
        return result;
    }

    /** Matches the exact shape documented in docs/administrator-guide.md &sect;6 "Enabling HTTPS". */
    private static Element buildSslConnector(Document doc, Integer port, String certificateFile, String certificateKeyFile) {
        Element connector = doc.createElement("Connector");
        connector.setAttribute("port", String.valueOf(port));
        connector.setAttribute("protocol", "org.apache.coyote.http11.Http11NioProtocol");
        connector.setAttribute("SSLEnabled", "true");
        connector.setAttribute("scheme", "https");
        connector.setAttribute("secure", "true");
        connector.setAttribute("maxThreads", "150");
        connector.appendChild(buildSslHostConfig(doc, certificateFile, certificateKeyFile));
        return connector;
    }

    private static Element buildSslHostConfig(Document doc, String certificateFile, String certificateKeyFile) {
        Element sslHostConfig = doc.createElement("SSLHostConfig");
        Element certificate = doc.createElement("Certificate");
        certificate.setAttribute("certificateFile", certificateFile);
        certificate.setAttribute("certificateKeyFile", certificateKeyFile);
        certificate.setAttribute("type", "RSA");
        sslHostConfig.appendChild(certificate);
        return sslHostConfig;
    }

    public record TomcatConnectorStatus(
            String path, boolean fileReadable, int httpPort, boolean httpsEnabled, Integer httpsPort,
            String certificateFile, String certificateKeyFile, String error) {
    }
}
