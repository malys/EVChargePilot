# CP-013 composition inventory

| Approved ingredient | Implementation medium | Boundary |
|---|---|---|
| 80dp top bar with back, title and delete-all | Native XML + existing EVSuite button/text styles | No invented navigation rail |
| Fixed 420dp reverse-chronological ledger | Native `ListView` with recycled Kotlin row views | Real stored summaries only; 72dp+ rows |
| Selected ledger state | Existing tonal raised surface plus text selection state | No decorative accent stripe |
| Detail title and totals | Native XML/TextViews | Only recorded/derived summary fields |
| Speed/power trace | Custom Android `View` drawing two nullable polylines | Omit the plot entirely when no track exists |
| Empty state | Native text and one back action | Explain how recording creates history |
| Single/all deletion confirmation | Dedicated full-screen native activity | Fail closed on unreadable or moving speed |

Composition commitments: list context remains visible while reading detail; one selected trip owns
the right pane; destructive actions are visible, never gestural; unavailable data stays an em dash
with an accessible reason. Generated comp values and its unsupported average-speed field are not
product requirements.
