package com.wiz.build;

public interface BuildLogger {

    void info(String message);

    void output(String text);

    static BuildLogger quiet() {
        return new BuildLogger() {
            @Override
            public void info(String message) {
            }

            @Override
            public void output(String text) {
            }
        };
    }

    static BuildLogger console() {
        return new BuildLogger() {
            @Override
            public void info(String message) {
                System.out.println(message);
            }

            @Override
            public void output(String text) {
                System.out.print(text);
            }
        };
    }
}
