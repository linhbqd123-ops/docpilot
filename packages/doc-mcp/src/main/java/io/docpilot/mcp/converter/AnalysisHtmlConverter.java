package io.docpilot.mcp.converter;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Produces a compact HTML variant for AI analysis.
 *
 * <p>The goal is to keep content and high-signal style/template hints while
 * stripping bulky noise such as embedded base64 assets, Word-specific metadata,
 * and verbose whitespace.</p>
 */
@Component
public class AnalysisHtmlConverter {

    private static final Pattern BODY_PATTERN = Pattern.compile("(?is)<body[^>]*>(.*?)</body>");
    private static final Pattern XML_DECL_PATTERN = Pattern.compile("(?is)<\\?xml[^>]*>");
    private static final Pattern DOCTYPE_PATTERN = Pattern.compile("(?is)<!doctype[^>]*>");
    private static final Pattern COMMENT_PATTERN = Pattern.compile("(?is)<!--.*?-->");
    private static final Pattern SCRIPT_PATTERN = Pattern.compile("(?is)<script[^>]*>.*?</script>");
    private static final Pattern META_LINK_PATTERN = Pattern.compile("(?is)<(?:meta|link)[^>]*>");
    private static final Pattern STYLE_TAG_PATTERN = Pattern.compile("(?is)<style[^>]*>(.*?)</style>");
    private static final Pattern DATA_URI_PATTERN = Pattern.compile(
        "(?is)(src|href)=(\"data:[^\"]*\"|'data:[^']*')"
    );
    private static final Pattern NOISY_ATTRIBUTE_PATTERN = Pattern.compile(
        "(?is)\\s(?:data-[\\w:-]+|aria-[\\w:-]+|id|lang|xml:lang|contenteditable|spellcheck)=(\"[^\"]*\"|'[^']*')"
    );
    private static final Pattern STYLE_ATTRIBUTE_PATTERN = Pattern.compile(
        "(?is)\\sstyle=(\"([^\"]*)\"|'([^']*)')"
    );

    private static final Set<String> ALLOWED_STYLE_PROPERTIES = Set.of(
        "background",
        "background-color",
        "border",
        "border-bottom",
        "border-color",
        "border-left",
        "border-right",
        "border-style",
        "border-top",
        "border-width",
        "color",
        "display",
        "font-family",
        "font-size",
        "font-style",
        "font-weight",
        "height",
        "letter-spacing",
        "line-height",
        "margin",
        "margin-bottom",
        "margin-left",
        "margin-right",
        "margin-top",
        "padding",
        "padding-bottom",
        "padding-left",
        "padding-right",
        "padding-top",
        "text-align",
        "text-decoration",
        "text-indent",
        "text-transform",
        "vertical-align",
        "white-space",
        "width"
    );

    public String convert(String html) {
        if (html == null || html.isBlank()) {
            return "";
        }

        String normalized = extractBody(html);
        normalized = XML_DECL_PATTERN.matcher(normalized).replaceAll("");
        normalized = DOCTYPE_PATTERN.matcher(normalized).replaceAll("");
        normalized = COMMENT_PATTERN.matcher(normalized).replaceAll("");
        normalized = SCRIPT_PATTERN.matcher(normalized).replaceAll("");
        normalized = META_LINK_PATTERN.matcher(normalized).replaceAll("");
        normalized = minifyStyleTags(normalized);
        normalized = DATA_URI_PATTERN.matcher(normalized).replaceAll("$1=\"[embedded-resource]\"");
        normalized = NOISY_ATTRIBUTE_PATTERN.matcher(normalized).replaceAll("");
        normalized = simplifyInlineStyles(normalized);
        normalized = normalized.replace("\r", "");
        normalized = normalized.replaceAll(">\\s+<", "><");
        normalized = normalized.replaceAll("[\\t ]+", " ");
        normalized = normalized.replaceAll("\\n{3,}", "\n\n");
        return normalized.trim();
    }

    private String extractBody(String html) {
        Matcher matcher = BODY_PATTERN.matcher(html);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return html;
    }

    private String minifyStyleTags(String html) {
        Matcher matcher = STYLE_TAG_PATTERN.matcher(html);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String css = matcher.group(1)
                .replaceAll("(?is)/\\*.*?\\*/", "")
                .replace("\r", "")
                .replaceAll("\\s+", " ")
                .replaceAll("\\s*([{}:;,])\\s*", "$1")
                .trim();
            String replacement = css.isBlank() ? "" : "<style>" + css + "</style>";
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    private String simplifyInlineStyles(String html) {
        Matcher matcher = STYLE_ATTRIBUTE_PATTERN.matcher(html);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String rawStyle = matcher.group(2) != null ? matcher.group(2) : matcher.group(3);
            String simplified = simplifyStyleValue(rawStyle);
            String replacement = simplified.isBlank() ? "" : " style=\"" + simplified + "\"";
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    private String simplifyStyleValue(String rawStyle) {
        if (rawStyle == null || rawStyle.isBlank()) {
            return "";
        }

        List<String> keptDeclarations = new ArrayList<>();
        for (String declaration : rawStyle.split(";")) {
            String trimmed = declaration.trim();
            if (trimmed.isBlank()) {
                continue;
            }

            int colonIndex = trimmed.indexOf(':');
            if (colonIndex <= 0) {
                continue;
            }

            String property = trimmed.substring(0, colonIndex).trim().toLowerCase();
            String value = trimmed.substring(colonIndex + 1).trim().replaceAll("\\s+", " ");
            if (property.startsWith("mso-") || property.startsWith("-aw-") || value.isBlank()) {
                continue;
            }
            if (!ALLOWED_STYLE_PROPERTIES.contains(property)) {
                continue;
            }
            keptDeclarations.add(property + ":" + value);
        }

        return String.join(";", keptDeclarations);
    }
}