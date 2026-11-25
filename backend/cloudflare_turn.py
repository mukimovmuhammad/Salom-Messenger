"""Issue short-lived Cloudflare TURN credentials from the SALOM backend."""

import http.client
import json
import logging
import re


logger = logging.getLogger(__name__)
TURN_URLS = {
    'turn:turn.cloudflare.com:3478?transport=udp',
    'turn:turn.cloudflare.com:443?transport=udp',
    'turn:turn.cloudflare.com:3478?transport=tcp',
    'turn:turn.cloudflare.com:80?transport=tcp',
    'turns:turn.cloudflare.com:5349?transport=tcp',
    'turns:turn.cloudflare.com:443?transport=tcp',
}
RESPONSE_LIMIT = 16_384


def issue_turn_servers(key_id: str, token: str, ttl: int) -> list[dict]:
    if not re.fullmatch(r'[A-Za-z0-9_-]{1,128}', key_id):
        logger.warning('Invalid Cloudflare TURN key ID')
        return []

    connection = http.client.HTTPSConnection('rtc.live.cloudflare.com', timeout=3)
    try:
        connection.request(
            'POST', f'/v1/turn/keys/{key_id}/credentials/generate-ice-servers',
            body=json.dumps({'ttl': ttl}),
            headers={'Authorization': f'Bearer {token}', 'Content-Type': 'application/json'},
        )
        response = connection.getresponse()
        if response.status != 201:
            logger.warning('Cloudflare TURN credential request failed (HTTP %d)', response.status)
            return []
        body = response.read(RESPONSE_LIMIT + 1)
        if len(body) > RESPONSE_LIMIT:
            logger.warning('Cloudflare TURN response exceeded the expected size')
            return []
        data = json.loads(body)
    except (OSError, ValueError, http.client.HTTPException) as error:
        logger.warning('Cloudflare TURN credential request failed (%s)', type(error).__name__)
        return []
    finally:
        connection.close()

    entries = data.get('iceServers') if isinstance(data, dict) else None
    if isinstance(entries, dict):
        entries = [entries]
    servers = []
    for entry in entries if isinstance(entries, list) else []:
        if not isinstance(entry, dict):
            continue
        username = entry.get('username')
        credential = entry.get('credential')
        if not isinstance(username, str) or not username or not isinstance(credential, str) or not credential:
            continue
        urls = entry.get('urls')
        if isinstance(urls, str):
            urls = [urls]
        if not isinstance(urls, list):
            continue
        allowed = list(dict.fromkeys(url for url in urls if isinstance(url, str) and url in TURN_URLS))
        if allowed:
            servers.append({'urls': allowed, 'username': username, 'credential': credential})
    if not servers:
        logger.warning('Cloudflare TURN returned no usable relay credentials')
    return servers
