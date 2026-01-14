package name.atlasclient.script;

public class ExampleScript implements Script {
    private boolean enabled = false;

    @Override public String id() { return "example"; }
    @Override public String displayName() { return "Example Script"; }
    @Override public String description() { return "A placeholder script you can toggle on/off in the UI."; }

    @Override public boolean isEnabled() { return enabled; }
    @Override public void setEnabled(boolean enabled) { this.enabled = enabled; }
}
