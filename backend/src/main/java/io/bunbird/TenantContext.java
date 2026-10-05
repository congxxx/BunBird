package io.bunbird;

/** Request scope is established by authenticated credentials, never by a body field. */
final class TenantContext implements AutoCloseable {
    record Identity(String tenant, String app, String user, String role, boolean platform) {}
    private static final ThreadLocal<Identity> CURRENT = new ThreadLocal<>();
    private final Identity previous;
    TenantContext(Identity identity) { previous=CURRENT.get(); CURRENT.set(identity); }
    static Identity current() { return CURRENT.get(); }
    static String tenant(String fallback) { return current()==null?fallback:current().tenant(); }
    static String app(String fallback) { return current()==null?fallback:current().app(); }
    @Override public void close() { if(previous==null)CURRENT.remove(); else CURRENT.set(previous); }
}
