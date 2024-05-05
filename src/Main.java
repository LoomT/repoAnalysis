import org.apache.commons.lang3.tuple.Pair;
import org.kohsuke.github.GHCommit;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;
import org.kohsuke.github.PagedIterable;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class Main {

    private record User(String name, String email) {}
    private record UserPair(User user1, User user2) {}
    private enum Arg {
        COMMITS,
        REPO,
        PAIR_NUM,
        PAIR_PERCENT,
        TOKEN
    }

    public static void main(String[] args) throws InterruptedException {
        Map<Arg, String> argMap;
        try {
            argMap = parseArgs(args);
        } catch (IllegalArgumentException e) {
            System.out.println("Error: " + e.getMessage());
            return;
        }

        GitHub g;
        GHRepository repo;
        try {
            System.out.println("Connecting to GitHub...");
            g = argMap.containsKey(Arg.TOKEN) ?
                    GitHub.connectUsingOAuth(argMap.get(Arg.TOKEN)) : GitHub.connectAnonymously();
            System.out.println("Connected to GitHub.");
            int remaining = g.getRateLimit().getRemaining();
            System.out.println("Remaining rate limit: " + remaining + "/" + g.getRateLimit().getLimit());
            if(remaining < 3) {
                System.out.println("Error: rate limit reached");
                return;
            }
            repo = g.getRepository(argMap.get(Arg.REPO));
        } catch (IOException e) {
            System.out.println("Error: " + e.getMessage());
            return;
        }
        System.out.println("Checking repo: " + repo.getName());

        Map<User, Map<String, Integer>> users = getUserWithFiles(repo, argMap, g);
        if (users == null) return;

        Map<UserPair, Integer> metrics = getMetrics(users);

        if(metrics.isEmpty()) {
            System.out.println("No pairs found");
            return;
        }
        int toShow;
        if(argMap.containsKey(Arg.PAIR_NUM) && Integer.parseInt(argMap.get(Arg.PAIR_NUM)) < metrics.size())
            toShow = Integer.parseInt(argMap.get(Arg.PAIR_NUM));
        else if (argMap.containsKey(Arg.PAIR_PERCENT)) {
            toShow = (int) Math.round(metrics.size() * (Double.parseDouble(argMap.get(Arg.PAIR_PERCENT)) / 100d));
            toShow = Math.clamp(toShow, 1, metrics.size());
        }
        else toShow = metrics.size();
        printResults(metrics, toShow);
    }

    /**
     * Calculate scores between all pairs of users
     *
     * @param users all users with files
     * @return scores
     */
    private static Map<UserPair, Integer> getMetrics(Map<User, Map<String, Integer>> users) {
        Map<UserPair, Integer> metrics = new HashMap<>();

        List<User> userIDs = users.keySet().stream().toList();
        for (int i = 0; i < users.size(); i++) {
            for (int j = 0; j < users.size(); j++) {
                if(j <= i) continue;
                int metric = 0;
                for(Map.Entry<String, Integer> entry : users.get(userIDs.get(i)).entrySet()) {
                    if(!users.get(userIDs.get(j)).containsKey(entry.getKey())) continue;
                    int value1 = entry.getValue();
                    int value2 = users.get(userIDs.get(j)).get(entry.getKey());
                    metric += Math.min(value1, value2);
                }
                metrics.put(new UserPair(userIDs.get(i), userIDs.get(j)), metric);
            }
        }
        return metrics;
    }

    /**
     * @param repo repo to pull data from
     * @param argMap arguments
     * @return users mapped to the files and the number of times they commited to them
     * @throws InterruptedException if executor service is interrupted
     */
    private static Map<User, Map<String, Integer>> getUserWithFiles(GHRepository repo, Map<Arg, String> argMap, GitHub g) throws InterruptedException {
        PagedIterable<GHCommit> commitsPaged = repo.listCommits().withPageSize(100); // minimize api calls
        List<GHCommit> commits = new ArrayList<>();
        org.kohsuke.github.PagedIterator<GHCommit> itr = commitsPaged.iterator();
        int limit = argMap.containsKey(Arg.COMMITS) ? Integer.parseInt(argMap.get(Arg.COMMITS)) : Integer.MAX_VALUE;
        int remaining;
        try {
            remaining = g.getRateLimit().getRemaining();
            while (itr.hasNext()) {
                remaining--;
                System.out.print(".");
                commits.addAll(itr.nextPage());
                if(commits.size() >= limit) break;
                if(remaining < 1) {
                    System.out.println("Error: rate limit reached");
                    return null;
                }
            }
        } catch (IOException e) {
            System.out.println("Error: " + e.getMessage());
            return null;
        }
        System.out.println();
        limit = Math.min(limit, commits.size());
        if(remaining < limit) {
            System.out.println("Error: rate limit reached");
            return null;
        }
        Map<User, Map<String, Integer>> users = new HashMap<>();
        Queue<Pair<User, List<String>>> files = new ConcurrentLinkedQueue<>();
        ExecutorService es = Executors.newFixedThreadPool(99); // concurrent api limit
        int badCommits = 0;
        for (int i = 0; i < limit; i++) {
            GHCommit commit = commits.get(i);
            User user;
            try {
                user = new User(commit.getCommitShortInfo().getAuthor().getName(),
                        commit.getCommitShortInfo().getAuthor().getEmail().toLowerCase());
            } catch (IOException e) {
                badCommits++;
                continue;
            }
            users.putIfAbsent(user, new HashMap<>());
            es.execute(() -> {
                try {
                    files.add(Pair.of(user, commit.listFiles().toList().stream().map(GHCommit.File::getFileName).toList()));
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }

        es.shutdown();
        boolean tasksFinished = es.awaitTermination(1, TimeUnit.MINUTES);
        es.close();
        if(!tasksFinished) {
            System.out.println("Not all commits parsed");
            return null;
        }
        System.out.println("All commits parsed");
        if(badCommits > 0) System.out.println(badCommits + " commits did not contain an author and were ignored");

        for(Pair<User, List<String>> pair : files) {
            User user = pair.getKey();
            for(String fileName : pair.getValue()) {
                users.get(user).merge(fileName, 1, Integer::sum);
            }
        }
        return users;
    }

    /**
     * @param args CLI arguments
     * @return parsed arguments
     * @throws IllegalArgumentException if there is an invalid argument
     */
    private static Map<Arg, String> parseArgs(String[] args) {
        if(args.length < 1 || args.length > 4) {
            System.out.println("""
                    Usage: java -jar repoAnalysis-uber.jar -[<option>=<value>]* <repoOwner/repoName>
                    Example: java -jar repoAnalysis-uber.jar -percent=20 -token=github_pat_123456789abc fizz/buzz""");
            throw new IllegalArgumentException();
        }
        Map<Arg, String> argMap = new HashMap<>();
        String repoName = args[args.length-1];
        if(!repoName.contains("/")) {
            throw new IllegalArgumentException("Repository name must be in format owner/rep");
        }
        argMap.put(Arg.REPO, repoName);

        for (int i = 0; i < args.length-1; i++) {
            String arg = args[i];
            if (arg.startsWith("-commits=")) {
                try {
                    int numCommits = Integer.parseInt(arg.substring(arg.indexOf("=") + 1));
                    argMap.put(Arg.COMMITS, String.valueOf(numCommits));
                    if (numCommits < 1) {
                        throw new IllegalArgumentException("The number of commits needs to be larger than 0");
                    }
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("Illegal number for -commits argument");
                }
            } else if (arg.startsWith("-token=")) {
                String token = arg.substring(arg.indexOf("=") + 1);
                argMap.put(Arg.TOKEN, token);
            } else if (arg.startsWith("-pairs")) {
                try {
                    String pairs = arg.substring(arg.indexOf("=") + 1);
                    argMap.put(Arg.PAIR_NUM, pairs);
                    if (Integer.parseInt(pairs) < 1) {
                        throw new IllegalArgumentException("The number of pairs needs to be larger than 0");
                    }
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("Illegal number for -pairs argument");
                }
            } else if (arg.startsWith("-percent")) {
                try {
                    String percent = arg.substring(arg.indexOf("=") + 1);
                    argMap.put(Arg.PAIR_PERCENT, percent);
                    if (Integer.parseInt(percent) < 1 || Integer.parseInt(percent) > 100) {
                        throw new IllegalArgumentException("The percentage needs to be between 1 and 100");
                    }
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("Illegal number for -percent argument");
                }
            } else {
                throw new IllegalArgumentException("Unknown argument: " + arg);
            }
        }

        if(argMap.containsKey(Arg.PAIR_NUM) && argMap.containsKey(Arg.PAIR_PERCENT)) {
            throw new IllegalArgumentException("-pairs and -percent can not be used together");
        }

        return argMap;
    }

    /**
     * Sorts and prints the results
     *
     * @param metrics all pairs and their scores
     * @param toShow number of pairs to print
     */
    private static void printResults(Map<UserPair, Integer> metrics, int toShow) {
        List<Map.Entry<UserPair, Integer>> sorted = metrics.entrySet()
                .stream().sorted(Comparator.comparingInt(Map.Entry::getValue))
                .toList().reversed();

        System.out.println("\nTop " + toShow + " pairs based on common files:");
        for (int i = 0; i < toShow; i++) {
            Map.Entry<UserPair, Integer> entry = sorted.get(i);
            String user1 = entry.getKey().user1.name;
            String user2 = entry.getKey().user2.name;
            int metric = entry.getValue();
            System.out.println(user1 + " and " + user2 + ": " + metric + " score");
        }
        int median = sorted.size() % 2 == 0 ?
                (sorted.get(sorted.size()/2).getValue() + sorted.get(sorted.size()/2 + 1).getValue()) / 2 :
                sorted.get(sorted.size()/2).getValue();
        System.out.println("\nNumber of pairs: " + sorted.size());
        System.out.println("Median score: " + median);
    }
}