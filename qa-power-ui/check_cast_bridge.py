"""Check discovery and device description of this lab's renderer from Android's network namespace."""
import http.client
import ipaddress
import json
import socket
import sys
import time
from urllib.parse import urljoin, urlsplit
import xml.etree.ElementTree as ET


def discover(peer):
    if not ipaddress.ip_address(peer).is_private:
        raise ValueError('Expected the renderer on the private container bridge')
    target = 'urn:schemas-upnp-org:device:MediaRenderer:1'
    request = (f'M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: "ssdp:discover"\r\n'
               f'MX: 2\r\nST: {target}\r\n\r\n').encode('ascii')
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
        sock.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_TTL, 1)
        sock.settimeout(.5)
        for _ in range(3):
            sock.sendto(request, ('239.255.255.250', 1900))
            deadline = time.monotonic() + 3
            while time.monotonic() < deadline:
                try:
                    data, sender = sock.recvfrom(8192)
                except socket.timeout:
                    continue
                if sender[0] != peer:
                    continue
                headers = {k.lower(): v.strip() for k, v in
                           (line.split(':', 1) for line in data.decode('latin1').splitlines() if ':' in line)}
                location = urlsplit(headers.get('location', ''))
                if headers.get('st') == target and location.scheme == 'http' and location.hostname == peer:
                    return location
    raise TimeoutError('No SSDP reply from the dedicated renderer on the private bridge')


def check(peer):
    location = discover(peer)
    connection = http.client.HTTPConnection(peer, location.port or 80, timeout=4)
    try:
        connection.request('GET', location.path + ('?' + location.query if location.query else ''))
        response = connection.getresponse()
        if response.status != 200:
            raise ValueError('Renderer description status ' + str(response.status))
        payload = response.read(65537)
    finally:
        connection.close()
    if len(payload) > 65536:
        raise ValueError('Unexpected renderer description length')
    device = ET.fromstring(payload)
    name = device.findtext('.//{*}friendlyName')
    services = [n.text for n in device.findall('.//{*}serviceType')]
    if name != 'Yfuse QA Renderer' or not any('service:AVTransport:' in s for s in services if s):
        raise ValueError('Unexpected renderer or missing AVTransport')
    service = next(s for s in device.findall('.//{*}service') if s.findtext('{*}serviceType') == 'urn:schemas-upnp-org:service:AVTransport:1')
    control = urlsplit(urljoin(location.geturl(), service.findtext('{*}controlURL')))
    if control.scheme != 'http' or control.hostname != peer or control.port != location.port or not control.path.startswith('/'):
        raise ValueError('Expected the dedicated renderer control URL')
    print(json.dumps({'friendlyName': name, 'services': services, 'descriptionBytes': len(payload), 'avTransportUrl': control.geturl()}))


if __name__ == '__main__':
    check(sys.argv[1])
