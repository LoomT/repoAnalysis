### About

Calculates top pairs of authors who contribute to the same files

### Running from command line

`./gradlew uberJar` to compile a jar file\
`java -jar repoAnalysis-uber.jar -[<option>=<value>]* <repoOwner/repoName>` to run the jar file

\* means 0 or more times

\<repoOwner/repoName>: repository

\<option>, \<value>: a command-line option with the corresponding value (see "Command-line options" below).

##### Examples:

`java -jar build/libs/repoAnalysis-uber.jar fizz/buzz`

`java -jar build/libs/repoAnalysis-uber.jar -commits=200 -token=github_pat_123456789abc -pairs=5 fizz/buzz`

### Command-line options

- -commits: positive integer of how many commits to look at from most recent, by default set to all 
- -pairs: number of top pairs to print, by default set to all
- -percent: percent of top pairs to print, between 1 and 100 inclusive (-pairs and -percent can not be used together)
- -token: GitHub personal access token for bigger API rate limit. By default, will use anonymous login which only has 60 api calls, while with the token it's 5000 calls. One commit consumes one API call to get the modified files