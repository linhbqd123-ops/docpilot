import typography from "@tailwindcss/typography";

/** @type {import('tailwindcss').Config} */
export default {
  content: ["./index.html", "./src/**/*.{ts,tsx}"],
  theme: {
    extend: {
      fontFamily: {
        sans: ["IBM Plex Sans", "Segoe UI", "system-ui", "sans-serif"],
        display: ["Newsreader", "Georgia", "serif"],
      },
      colors: {
        docpilot: {
          bg: "rgb(var(--docpilot-bg) / <alpha-value>)",
          shell: "rgb(var(--docpilot-shell) / <alpha-value>)",
          rail: "rgb(var(--docpilot-rail) / <alpha-value>)",
          sidebar: "rgb(var(--docpilot-sidebar) / <alpha-value>)",
          surface: "rgb(var(--docpilot-surface) / <alpha-value>)",
          panel: "rgb(var(--docpilot-panel) / <alpha-value>)",
          panelAlt: "rgb(var(--docpilot-panel-alt) / <alpha-value>)",
          hover: "rgb(var(--docpilot-hover) / <alpha-value>)",
          border: "rgb(var(--docpilot-border) / <alpha-value>)",
          text: "rgb(var(--docpilot-text) / <alpha-value>)",
          textStrong: "rgb(var(--docpilot-text-strong) / <alpha-value>)",
          muted: "rgb(var(--docpilot-muted) / <alpha-value>)",
          accent: "rgb(var(--docpilot-accent) / <alpha-value>)",
          accentHover: "rgb(var(--docpilot-accent-hover) / <alpha-value>)",
          accentContrast: "rgb(var(--docpilot-accent-contrast) / <alpha-value>)",
          accentSoft: "rgb(var(--docpilot-accent-soft) / <alpha-value>)",
          success: "rgb(var(--docpilot-success) / <alpha-value>)",
          successSoft: "rgb(var(--docpilot-success-soft) / <alpha-value>)",
          successText: "rgb(var(--docpilot-success-text) / <alpha-value>)",
          warning: "rgb(var(--docpilot-warning) / <alpha-value>)",
          warningSoft: "rgb(var(--docpilot-warning-soft) / <alpha-value>)",
          warningText: "rgb(var(--docpilot-warning-text) / <alpha-value>)",
          danger: "rgb(var(--docpilot-danger) / <alpha-value>)",
          dangerSoft: "rgb(var(--docpilot-danger-soft) / <alpha-value>)",
          dangerText: "rgb(var(--docpilot-danger-text) / <alpha-value>)",
          paper: "rgb(var(--docpilot-paper) / <alpha-value>)",
          paperBorder: "rgb(var(--docpilot-paper-border) / <alpha-value>)",
          ink: "rgb(var(--docpilot-ink) / <alpha-value>)",
        },
      },
      boxShadow: {
        glow: "var(--docpilot-shadow-glow)",
        active: "var(--docpilot-shadow-active)",
        paper: "var(--docpilot-shadow-paper)",
      },
      backgroundImage: {
        mesh: "var(--docpilot-mesh)",
      },
    },
  },
  plugins: [typography],
};
