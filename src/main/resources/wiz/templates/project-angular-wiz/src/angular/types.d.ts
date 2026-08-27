declare global {
  interface Window {
    WizRoute?: any;
    MonacoEnvironment?: any;
    __APP_CONFIG__?: {
      apiPrefix?: string;
      [key: string]: unknown;
    };
  }
  interface Navigator {
    userLanguage?: string;
  }
}

declare const WizRoute: any;
export {};
