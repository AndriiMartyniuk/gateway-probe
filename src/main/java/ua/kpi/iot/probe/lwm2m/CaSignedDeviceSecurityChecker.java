package ua.kpi.iot.probe.lwm2m;

import org.eclipse.leshan.core.peer.LwM2mPeer;
import org.eclipse.leshan.core.peer.X509Identity;
import org.eclipse.leshan.servers.security.SecurityChecker;
import org.eclipse.leshan.servers.security.SecurityInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Accepts any device that authenticated over TLS with an X.509 certificate whose common name equals its endpoint
 * name, even if the device has no entry in the security store. The certificate chain was already verified against the
 * trusted CA certificates during the TLS handshake, so "signed by our CA and CN = endpoint" is the identity check.
 * This lets a fleet of thousands of devices connect without listing each one.
 *
 * <p>
 * Devices that do have a security-store entry, and devices without TLS, are checked exactly as by Leshan.
 */
public class CaSignedDeviceSecurityChecker extends SecurityChecker {

	private static final Logger log = LoggerFactory.getLogger(CaSignedDeviceSecurityChecker.class);

	@Override
	public boolean checkSecurityInfo(String endpoint, LwM2mPeer client, SecurityInfo securityInfo) {
		if (securityInfo == null && client.getIdentity() instanceof X509Identity x509) {
			boolean matches = endpoint.equals(x509.getX509CommonName());
			if (!matches) {
				log.debug("Rejecting '{}': certificate CN '{}' does not match the endpoint name", endpoint,
						x509.getX509CommonName());
			}
			return matches;
		}
		return super.checkSecurityInfo(endpoint, client, securityInfo);
	}

}
