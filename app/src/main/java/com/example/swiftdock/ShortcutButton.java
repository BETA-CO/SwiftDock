package com.example.swiftdock;

import java.io.Serializable;

public class ShortcutButton implements Serializable {
    private String id;
    private String title;
    private String color;
    private String icon;
    private String actionType;
    private String actionData;

    // Getters and Setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getColor() { return color; }
    public void setColor(String color) { this.color = color; }

    public String getIcon() { return icon; }
    public void setIcon(String icon) { this.icon = icon; }

    public String getActionType() { return actionType; }
    public void setActionType(String actionType) { this.actionType = actionType; }

    public String getActionData() { return actionData; }
    public void setActionData(String actionData) { this.actionData = actionData; }
}
