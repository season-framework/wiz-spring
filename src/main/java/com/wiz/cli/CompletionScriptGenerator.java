package com.wiz.cli;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import picocli.AutoComplete;
import picocli.CommandLine;
import picocli.CommandLine.Help;
import picocli.CommandLine.Model.CommandSpec;

final class CompletionScriptGenerator {

    private static final Pattern ANSI_ESCAPE = Pattern.compile("\u001B\\[[0-9;]*m");

    private CompletionScriptGenerator() {
    }

    static String generate(CommandLine root) {
        List<CommandContext> contexts = new ArrayList<>();
        collectContexts(root, List.of(), contexts);

        StringBuilder script = new StringBuilder(AutoComplete.bash(root.getCommandName(), root));
        script.append("\n# Context-sensitive help shown alongside completion candidates.\n")
                .append("function __wiz_spring_completion_cursor_supported() {\n")
                .append("  [[ -t 2 && \"${TERM:-dumb}\" != \"dumb\" ]]\n")
                .append("}\n\n")
                .append("function __wiz_spring_completion_color_enabled() {\n")
                .append("  case \"${WIZ_SPRING_COMPLETION_COLOR:-auto}\" in\n")
                .append("    0|false|FALSE|no|NO|off|OFF|never|NEVER) return 1 ;;\n")
                .append("    1|true|TRUE|yes|YES|on|ON|always|ALWAYS) return 0 ;;\n")
                .append("  esac\n")
                .append("  [[ -z \"${NO_COLOR-}\" ]] || return 1\n")
                .append("  __wiz_spring_completion_cursor_supported\n")
                .append("}\n\n")
                .append("function __wiz_spring_completion_input_tail_rows() {\n")
                .append("  local LC_ALL=C\n")
                .append("  local columns=\"${COLUMNS:-80}\"\n")
                .append("  if [[ ! \"${columns}\" =~ ^[1-9][0-9]*$ ]]; then\n")
                .append("    columns=80\n")
                .append("  fi\n")
                .append("  local input_tail=\"${COMP_LINE:${COMP_POINT}}\"\n")
                .append("  local tail_length=\"${#input_tail}\"\n")
                .append("  if (( tail_length == 0 )); then\n")
                .append("    printf '0'\n")
                .append("  else\n")
                .append("    printf '%d' \"$(( (tail_length + columns - 1) / columns ))\"\n")
                .append("  fi\n")
                .append("}\n\n")
                .append("function __wiz_spring_completion_clear_panel() {\n")
                .append("  if __wiz_spring_completion_cursor_supported; then\n")
                .append("    local tail_rows=\"$(__wiz_spring_completion_input_tail_rows)\"\n")
                .append("    printf '\\0337\\033[%dB\\r\\033[J\\0338' \"$((tail_rows + 1))\" >&2\n")
                .append("  fi\n")
                .append("}\n\n")
                .append("function __wiz_spring_completion_reserve_panel() {\n")
                .append("  local panel_rows=\"${1:-1}\"\n")
                .append("  local row=0\n")
                .append("  while (( row < panel_rows )); do\n")
                .append("    printf '\\033D' >&2\n")
                .append("    ((row += 1))\n")
                .append("  done\n")
                .append("  printf '\\033[%dA' \"${panel_rows}\" >&2\n")
                .append("}\n\n")
                .append("function __wiz_spring_completion_begin_panel() {\n")
                .append("  local panel_rows=\"${1:-1}\"\n")
                .append("  if __wiz_spring_completion_cursor_supported; then\n")
                .append("    local tail_rows=\"$(__wiz_spring_completion_input_tail_rows)\"\n")
                .append("    if [[ \"${__WIZ_SPRING_COMPLETION_REUSE_PANEL:-false}\" != true ]]; then\n")
                .append("      __wiz_spring_completion_reserve_panel \"$((panel_rows + tail_rows))\"\n")
                .append("    fi\n")
                .append("    __WIZ_SPRING_COMPLETION_CURSOR_SAVED=true\n")
                .append("    printf '\\0337\\033[%dB\\r\\033[J' \"$((tail_rows + 1))\" >&2\n")
                .append("  else\n")
                .append("    __WIZ_SPRING_COMPLETION_CURSOR_SAVED=false\n")
                .append("    printf '\\n' >&2\n")
                .append("  fi\n")
                .append("}\n\n")
                .append("function __wiz_spring_completion_end_panel() {\n")
                .append("  if [[ \"${__WIZ_SPRING_COMPLETION_CURSOR_SAVED:-false}\" = true ]]; then\n")
                .append("    printf '\\0338' >&2\n")
                .append("  fi\n")
                .append("}\n\n")
                .append("function __wiz_spring_completion_dispatch_help() {\n");

        appendContextDispatch(script, contexts);
        script.append("}\n\n")
                .append("function __wiz_spring_completion_show_help() {\n")
                .append("  case \"${WIZ_SPRING_COMPLETION_HELP:-true}\" in\n")
                .append("    0|false|FALSE|no|NO|off|OFF)\n")
                .append("      if [[ \"${__WIZ_SPRING_COMPLETION_HELP_VISIBLE:-false}\" = true ]]; then\n")
                .append("        __wiz_spring_completion_clear_panel\n")
                .append("        __WIZ_SPRING_COMPLETION_HELP_VISIBLE=false\n")
                .append("      fi\n")
                .append("      return 0\n")
                .append("      ;;\n")
                .append("  esac\n")
                .append("\n")
                .append("  local help_key=\"${COMP_LINE-}:${COMP_POINT-}\"\n")
                .append("  if [[ \"${__WIZ_SPRING_COMPLETION_LAST_HELP-}\" = \"${help_key}\" ]]; then\n")
                .append("    local __WIZ_SPRING_COMPLETION_REUSE_PANEL=")
                .append("\"${__WIZ_SPRING_COMPLETION_HELP_VISIBLE:-false}\"\n")
                .append("    __wiz_spring_completion_dispatch_help terminal\n")
                .append("    __WIZ_SPRING_COMPLETION_SUPPRESS_MATCHES=true\n")
                .append("    __WIZ_SPRING_COMPLETION_HELP_VISIBLE=true\n")
                .append("    return 0\n")
                .append("  fi\n")
                .append("  __WIZ_SPRING_COMPLETION_LAST_HELP=\"${help_key}\"\n")
                .append("  __wiz_spring_completion_dispatch_help terminal\n")
                .append("  __WIZ_SPRING_COMPLETION_HELP_VISIBLE=true\n")
                .append("}\n\n");
        for (CommandContext context : contexts) {
            appendHelpFunction(script, context);
        }

        script.append("function _complete_wiz_spring_with_help() {\n")
                .append("  local __WIZ_SPRING_COMPLETION_SUPPRESS_MATCHES=false\n")
                .append("  _complete_wiz-spring \"$@\"\n")
                .append("  local completion_status=$?\n")
                .append("  __wiz_spring_completion_show_help\n")
                .append("  if [[ \"${__WIZ_SPRING_COMPLETION_SUPPRESS_MATCHES}\" = true ]]; then\n")
                .append("    COMPREPLY=()\n")
                .append("    compopt +o default 2>/dev/null || true\n")
                .append("    completion_status=0\n")
                .append("  fi\n")
                .append("  return \"${completion_status}\"\n")
                .append("}\n\n")
                .append("function __wiz_spring_completion_collect_zsh_matches() {\n")
                .append("  emulate -L sh\n")
                .append("  local current=\"$1\"\n")
                .append("  local -x COMP_LINE=\"$2\"\n")
                .append("  local -x COMP_POINT=\"$3\"\n")
                .append("  shift 3\n")
                .append("  local -x COMP_CWORD=$((current - 1))\n")
                .append("  local -a COMP_WORDS COMPREPLY BASH_VERSINFO\n")
                .append("  COMP_WORDS=(\"$@\")\n")
                .append("  BASH_VERSINFO=(2 05b 0 1 release)\n")
                .append("  _complete_wiz-spring\n")
                .append("  __WIZ_SPRING_COMPLETION_ZSH_MATCHES=(\"${COMPREPLY[@]}\")\n")
                .append("}\n\n")
                .append("function _complete_wiz_spring_zsh_with_help() {\n")
                .append("  local -a matches COMP_WORDS __WIZ_SPRING_COMPLETION_ZSH_MATCHES\n")
                .append("  local __WIZ_SPRING_COMPLETION_ZSH_HELP=\"\"\n")
                .append("  __wiz_spring_completion_collect_zsh_matches ")
                .append("\"$CURRENT\" \"$BUFFER\" \"$CURSOR\" \"${words[@]}\"\n")
                .append("  matches=(\"${__WIZ_SPRING_COMPLETION_ZSH_MATCHES[@]}\")\n")
                .append("  COMP_WORDS=(\"${words[@]}\")\n")
                .append("  case \"${WIZ_SPRING_COMPLETION_HELP:-true}\" in\n")
                .append("    0|false|FALSE|no|NO|off|OFF) ;;\n")
                .append("    *) __wiz_spring_completion_dispatch_help zsh ;;\n")
                .append("  esac\n")
                .append("  if (( ${#matches[@]} > 0 )); then\n")
                .append("    if [[ -n \"${__WIZ_SPRING_COMPLETION_ZSH_HELP}\" ]]; then\n")
                .append("      compadd -X \"${__WIZ_SPRING_COMPLETION_ZSH_HELP}\" -a matches\n")
                .append("    else\n")
                .append("      compadd -a matches\n")
                .append("    fi\n")
                .append("    return $?\n")
                .append("  fi\n")
                .append("  if [[ -n \"${__WIZ_SPRING_COMPLETION_ZSH_HELP}\" ]]; then\n")
                .append("    _default -X \"${__WIZ_SPRING_COMPLETION_ZSH_HELP}\"\n")
                .append("  else\n")
                .append("    _default\n")
                .append("  fi\n")
                .append("}\n\n")
                .append("if [[ -n \"${ZSH_VERSION-}\" ]]; then\n")
                .append("  compdef _complete_wiz_spring_zsh_with_help ")
                .append(shellQuote(root.getCommandName())).append(" ")
                .append(shellQuote(root.getCommandName() + ".sh")).append(" ")
                .append(shellQuote(root.getCommandName() + ".bash")).append("\n")
                .append("else\n")
                .append("  complete -F _complete_wiz_spring_with_help -o default ")
                .append(shellQuote(root.getCommandName())).append(" ")
                .append(shellQuote(root.getCommandName() + ".sh")).append(" ")
                .append(shellQuote(root.getCommandName() + ".bash")).append("\n")
                .append("fi\n");
        return script.toString();
    }

    private static void collectContexts(CommandLine command, List<PathSegment> path, List<CommandContext> contexts) {
        contexts.add(new CommandContext(path, command));
        Set<CommandSpec> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (CommandLine child : command.getSubcommands().values()) {
            if (!seen.add(child.getCommandSpec()) || child.getCommandSpec().usageMessage().hidden()) {
                continue;
            }
            ArrayList<String> names = new ArrayList<>();
            names.add(child.getCommandName());
            names.addAll(Arrays.asList(child.getCommandSpec().aliases()));
            ArrayList<PathSegment> childPath = new ArrayList<>(path);
            childPath.add(new PathSegment(List.copyOf(names)));
            collectContexts(child, List.copyOf(childPath), contexts);
        }
    }

    private static void appendContextDispatch(StringBuilder script, List<CommandContext> contexts) {
        List<CommandContext> commands = contexts.stream()
                .filter(context -> !context.path().isEmpty())
                .sorted(Comparator.comparingInt((CommandContext context) -> context.path().size()).reversed())
                .toList();
        boolean first = true;
        for (CommandContext context : commands) {
            script.append(first ? "  if " : "  elif ")
                    .append(matchExpression(context.path()))
                    .append("; then\n")
                    .append("    ").append(functionName(context.path())).append(" \"$@\"\n");
            first = false;
        }
        if (first) {
            script.append("  ").append(functionName(List.of())).append(" \"$@\"\n");
        } else {
            script.append("  else\n")
                    .append("    ").append(functionName(List.of())).append(" \"$@\"\n")
                    .append("  fi\n");
        }
    }

    private static String matchExpression(List<PathSegment> path) {
        return pathVariants(path).stream()
                .map(variant -> "CompWordsContainsArray " + variant.stream()
                        .map(CompletionScriptGenerator::shellQuote)
                        .collect(Collectors.joining(" ")))
                .collect(Collectors.joining(" || "));
    }

    private static List<List<String>> pathVariants(List<PathSegment> path) {
        List<List<String>> variants = new ArrayList<>();
        variants.add(new ArrayList<>());
        for (PathSegment segment : path) {
            List<List<String>> expanded = new ArrayList<>();
            for (List<String> variant : variants) {
                for (String name : segment.names()) {
                    ArrayList<String> next = new ArrayList<>(variant);
                    next.add(name);
                    expanded.add(next);
                }
            }
            variants = expanded;
        }
        return variants;
    }

    private static void appendHelpFunction(StringBuilder script, CommandContext context) {
        String help = context.command().getUsageMessage(Help.Ansi.OFF).stripTrailing();
        List<String> helpLines = help.lines().toList();
        String styledHelp = context.command().getUsageMessage(Help.Ansi.ON).stripTrailing();
        List<String> styledHelpLines = styledHelp.lines().toList();
        script.append("function ").append(functionName(context.path())).append("() {\n")
                .append("  if [[ \"${1:-terminal}\" = zsh ]]; then\n")
                .append("    if __wiz_spring_completion_color_enabled; then\n")
                .append("      __WIZ_SPRING_COMPLETION_ZSH_HELP=")
                .append(shellQuote(zshPromptStyled(styledHelp))).append("\n")
                .append("    else\n")
                .append("      __WIZ_SPRING_COMPLETION_ZSH_HELP=")
                .append(shellQuote(zshPromptPlain(help))).append("\n")
                .append("    fi\n")
                .append("    return 0\n")
                .append("  fi\n")
                .append("  __wiz_spring_completion_begin_panel ").append(helpLines.size() + 1).append("\n")
                .append("  if __wiz_spring_completion_color_enabled; then\n")
                .append("    printf '%s\\n'");
        for (String line : styledHelpLines) {
            script.append(" \\\n    ").append(shellQuote(line));
        }
        script.append(" >&2\n")
                .append("  else\n")
                .append("    printf '%s\\n'");
        for (String line : helpLines) {
            script.append(" \\\n    ").append(shellQuote(line));
        }
        script.append(" >&2\n")
                .append("  fi\n")
                .append("  __wiz_spring_completion_end_panel\n")
                .append("}\n\n");
    }

    private static String zshPromptPlain(String value) {
        return value.replace("%", "%%");
    }

    private static String zshPromptStyled(String value) {
        String escaped = zshPromptPlain(value);
        Matcher matcher = ANSI_ESCAPE.matcher(escaped);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(result, Matcher.quoteReplacement("%{" + matcher.group() + "%}"));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private static String functionName(List<PathSegment> path) {
        StringBuilder name = new StringBuilder("__wiz_spring_completion_help");
        for (PathSegment segment : path) {
            name.append("_").append(sanitize(segment.names().getFirst()));
        }
        return name.toString();
    }

    private static String sanitize(String value) {
        return value.replaceAll("[^A-Za-z0-9_]", "_");
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    private record PathSegment(List<String> names) {
    }

    private record CommandContext(List<PathSegment> path, CommandLine command) {
    }
}
