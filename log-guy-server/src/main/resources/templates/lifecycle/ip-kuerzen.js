// Shortens every IP address on its way into the archive: IPv4 to the first three octets with a zero, IPv6 to the
// first 32 bits, the block of a provider. Always active, a file scripts/lifecycle/ip-kuerzen.js replaces this template.

export const meta = { apps: ["*"] };

export function line(text) {
    return maskIp(text);
}
