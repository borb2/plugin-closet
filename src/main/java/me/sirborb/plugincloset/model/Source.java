package me.sirborb.plugincloset.model;

public enum Source {
    MODRINTH("Modrinth"),
    HANGAR("Hangar");

    private final String display;

    Source(String display) {
        this.display = display;
    }

    public String display() {
        return display;
    }
}
