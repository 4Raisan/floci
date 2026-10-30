package io.github.hectorvent.floci.services.ecs;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.ecs.model.ContainerInstance;
import io.github.hectorvent.floci.services.ecs.model.EcsTask;
import io.github.hectorvent.floci.services.ecs.model.TaskStatus;

import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** Parses the ECS cluster query language once, before selecting and paging instances. */
final class ContainerInstanceQuery {
    private final String expression;
    private final Function<ContainerInstance, List<EcsTask>> tasks;
    private int position;

    private ContainerInstanceQuery(String expression, Function<ContainerInstance, List<EcsTask>> tasks) {
        this.expression = expression;
        this.tasks = tasks;
    }

    static Predicate<ContainerInstance> compile(String expression,
                                               Function<ContainerInstance, List<EcsTask>> tasks) {
        if (expression == null) {
            return ignored -> true;
        }
        ContainerInstanceQuery parser = new ContainerInstanceQuery(expression, tasks);
        Predicate<ContainerInstance> result = parser.or();
        parser.space();
        if (parser.position != expression.length()) {
            throw invalid();
        }
        return result;
    }

    private Predicate<ContainerInstance> or() {
        Predicate<ContainerInstance> result = and();
        while (take("||") || take("or")) {
            result = result.or(and());
        }
        return result;
    }

    private Predicate<ContainerInstance> and() {
        Predicate<ContainerInstance> result = unary();
        while (take("&&") || take("and")) {
            result = result.and(unary());
        }
        return result;
    }

    private Predicate<ContainerInstance> unary() {
        if (take("!") || take("not")) {
            return unary().negate();
        }
        if (take("(")) {
            Predicate<ContainerInstance> result = or();
            require(")");
            return result;
        }
        return comparison();
    }

    private Predicate<ContainerInstance> comparison() {
        String subject = value();
        boolean attribute = subject.startsWith("attribute:") && subject.length() > "attribute:".length();
        boolean ordered = List.of("runningTasksCount", "agentVersion", "registeredAt").contains(subject);
        if (!attribute && !ordered && !List.of("agentConnected", "ec2InstanceId", "task:group").contains(subject)) {
            throw invalid();
        }
        String operator = operator();
        if (operator.equals("exists") || operator.equals("not_exists")) {
            if (!attribute) {
                throw invalid();
            }
            Predicate<ContainerInstance> exists = instance -> instance.getAttributes().stream()
                    .anyMatch(a -> a.name().equals(subject.substring("attribute:".length())));
            return operator.equals("exists") ? exists : exists.negate();
        }
        if (ordered && !List.of("==", "!=", ">", ">=", "<", "<=").contains(operator)
                || !ordered && !attribute && List.of(">", ">=", "<", "<=").contains(operator)) {
            throw invalid();
        }
        List<String> arguments = new ArrayList<>();
        if (operator.equals("in") || operator.equals("not_in")) {
            require("[");
            arguments.add(value());
            while (take(",")) {
                arguments.add(value());
            }
            require("]");
        } else {
            arguments.add(operator.equals("matches") || operator.equals("not_matches") ? patternValue() : value());
        }
        Predicate<String> match = matcher(subject, operator, arguments);
        boolean negative = List.of("!=", "not_in", "not_matches").contains(operator);
        return instance -> {
            List<String> values = values(instance, subject);
            boolean matched = values.stream().filter(v -> v != null).anyMatch(match);
            return negative ? !matched : matched;
        };
    }

    private static Predicate<String> matcher(String subject, String operator, List<String> arguments) {
        String argument = arguments.getFirst();
        if (operator.equals("matches") || operator.equals("not_matches")) {
            try {
                Pattern pattern = Pattern.compile(argument);
                return actual -> pattern.matcher(actual).matches();
            } catch (PatternSyntaxException e) {
                throw invalid();
            }
        }
        if (operator.equals("in") || operator.equals("not_in")) {
            return arguments::contains;
        }
        Function<String, ComparableValue> comparable = switch (subject) {
            case "runningTasksCount" -> value -> new ComparableValue(List.of(number(value)));
            case "agentVersion" -> value -> new ComparableValue(version(value));
            case "registeredAt" -> value -> new ComparableValue(List.of(BigInteger.valueOf(date(value).getEpochSecond()),
                    BigInteger.valueOf(date(value).getNano())));
            default -> null;
        };
        if (comparable != null) {
            ComparableValue expected = comparable.apply(argument);
            return actual -> compare(comparable.apply(actual).compareTo(expected), operator);
        }
        return actual -> compare(actual.compareTo(argument), operator);
    }

    private static boolean compare(int comparison, String operator) {
        return switch (operator) {
            case "==", "!=" -> comparison == 0;
            case ">" -> comparison > 0;
            case ">=" -> comparison >= 0;
            case "<" -> comparison < 0;
            case "<=" -> comparison <= 0;
            default -> throw invalid();
        };
    }

    private List<String> values(ContainerInstance instance, String subject) {
        if (subject.startsWith("attribute:")) {
            return instance.getAttributes().stream()
                    .filter(a -> a.name().equals(subject.substring("attribute:".length())))
                    .map(a -> a.value()).toList();
        }
        String value = switch (subject) {
            case "runningTasksCount" -> Long.toString(tasks.apply(instance).stream()
                    .filter(task -> TaskStatus.RUNNING.name().equals(task.getDesiredStatus())).count());
            case "agentConnected" -> Boolean.toString(instance.isAgentConnected());
            case "ec2InstanceId" -> instance.getEc2InstanceId();
            case "agentVersion" -> instance.getVersionInfo() != null
                    && instance.getVersionInfo().get("agentVersion") instanceof String version ? version : null;
            case "registeredAt" -> instance.getRegisteredAt() != null ? instance.getRegisteredAt().toString() : null;
            default -> null;
        };
        if (subject.equals("task:group")) {
            return tasks.apply(instance).stream()
                    .filter(task -> !TaskStatus.STOPPED.name().equals(task.getLastStatus()))
                    .map(EcsTask::getGroup).filter(group -> group != null).distinct().toList();
        }
        return value == null ? List.of() : List.of(value);
    }

    private String operator() {
        for (String symbol : List.of("==", "!=", ">=", "<=", "=~", "!~", "!exists", "!in", ">", "<")) {
            if (take(symbol)) {
                return switch (symbol) {
                    case "=~" -> "matches";
                    case "!~" -> "not_matches";
                    case "!exists" -> "not_exists";
                    case "!in" -> "not_in";
                    default -> symbol;
                };
            }
        }
        String word = value();
        return switch (word) {
            case "equals" -> "==";
            case "not_equals" -> "!=";
            case "greater_than" -> ">";
            case "greater_than_equal" -> ">=";
            case "less_than" -> "<";
            case "less_than_equal" -> "<=";
            case "exists", "not_exists", "in", "not_in", "matches", "not_matches" -> word;
            default -> throw invalid();
        };
    }

    private String value() {
        space();
        int start = position;
        if (position < expression.length() && (expression.charAt(position) == '\''
                || expression.charAt(position) == '"')) {
            char quote = expression.charAt(position++);
            StringBuilder value = new StringBuilder();
            while (position < expression.length()) {
                char next = expression.charAt(position++);
                if (next == quote) {
                    return value.toString();
                }
                if (next == '\\' && position < expression.length()
                        && (expression.charAt(position) == quote || expression.charAt(position) == '\\')) {
                    next = expression.charAt(position++);
                }
                value.append(next);
            }
            throw invalid();
        }
        while (position < expression.length() && !Character.isWhitespace(expression.charAt(position))
                && "()[],=!<>|&".indexOf(expression.charAt(position)) < 0) {
            position++;
        }
        if (start == position) {
            throw invalid();
        }
        return expression.substring(start, position);
    }

    private String patternValue() {
        space();
        if (position < expression.length() && (expression.charAt(position) == '\''
                || expression.charAt(position) == '"')) {
            return value();
        }
        int start = position;
        while (position < expression.length() && !Character.isWhitespace(expression.charAt(position))
                && "()".indexOf(expression.charAt(position)) < 0
                && !expression.startsWith("&&", position) && !expression.startsWith("||", position)) {
            position++;
        }
        if (start == position) {
            throw invalid();
        }
        return expression.substring(start, position);
    }

    private boolean take(String token) {
        space();
        if (!expression.startsWith(token, position)) {
            return false;
        }
        int end = position + token.length();
        if (Character.isLetter(token.charAt(token.length() - 1)) && end < expression.length()
                && (Character.isLetterOrDigit(expression.charAt(end)) || expression.charAt(end) == '_')) {
            return false;
        }
        position = end;
        return true;
    }

    private void require(String token) {
        if (!take(token)) {
            throw invalid();
        }
    }

    private void space() {
        while (position < expression.length() && Character.isWhitespace(expression.charAt(position))) {
            position++;
        }
    }

    private static BigInteger number(String value) {
        if (!value.matches("[0-9]+")) {
            throw invalid();
        }
        return new BigInteger(value);
    }

    private static List<BigInteger> version(String value) {
        return Arrays.stream(value.split("\\.", -1)).map(ContainerInstanceQuery::number).toList();
    }

    private static Instant date(String value) {
        try {
            if (value.length() == 10) {
                return LocalDate.parse(value).atStartOfDay().toInstant(ZoneOffset.UTC);
            }
            try {
                return Instant.parse(value);
            } catch (DateTimeParseException e) {
                return LocalDateTime.parse(value).toInstant(ZoneOffset.UTC);
            }
        } catch (DateTimeParseException e) {
            throw invalid();
        }
    }

    private record ComparableValue(List<BigInteger> parts) implements Comparable<ComparableValue> {
        @Override
        public int compareTo(ComparableValue other) {
            for (int i = 0; i < Math.max(parts.size(), other.parts.size()); i++) {
                BigInteger left = i < parts.size() ? parts.get(i) : BigInteger.ZERO;
                BigInteger right = i < other.parts.size() ? other.parts.get(i) : BigInteger.ZERO;
                int comparison = left.compareTo(right);
                if (comparison != 0) {
                    return comparison;
                }
            }
            return 0;
        }
    }

    private static AwsException invalid() {
        return new AwsException("InvalidParameterException", "Invalid cluster query filter.", 400);
    }
}
