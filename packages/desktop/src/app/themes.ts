export const THEME_REGISTRY = {
  dark: {
    id: "dark",
    label: "Midnight",
    shortLabel: "Dark",
    appearance: "dark",
    description: "Dense contrast for long editing sessions and low-light work.",
    preview: {
      chrome: "#111723",
      panel: "#1b2434",
      surface: "#223248",
      accent: "#4a9eff",
      text: "#f8fbff",
    },
  },
  light: {
    id: "light",
    label: "Light+",
    shortLabel: "Light+",
    appearance: "light",
    description: "VS Code inspired light workspace with neutral panels and editor-blue accents.",
    preview: {
      chrome: "#f3f3f3",
      panel: "#ffffff",
      surface: "#f8f8f8",
      accent: "#007acc",
      text: "#1e1e1e",
    },
  },
} as const;

export type ThemeMode = keyof typeof THEME_REGISTRY;
export type ThemeDefinition = (typeof THEME_REGISTRY)[ThemeMode];

export const DEFAULT_THEME: ThemeMode = "dark";
export const THEME_OPTIONS = Object.values(THEME_REGISTRY) as ThemeDefinition[];

export function isThemeMode(value: string): value is ThemeMode {
  return value in THEME_REGISTRY;
}

export function getThemeDefinition(theme: ThemeMode): ThemeDefinition {
  return THEME_REGISTRY[theme];
}

export function getNextTheme(theme: ThemeMode): ThemeMode {
  const options = THEME_OPTIONS.map((option) => option.id);
  const currentIndex = options.indexOf(theme);

  if (currentIndex === -1) {
    return DEFAULT_THEME;
  }

  return options[(currentIndex + 1) % options.length];
}