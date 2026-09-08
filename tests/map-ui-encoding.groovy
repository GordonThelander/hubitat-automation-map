#!/usr/bin/env groovy

File sourceFile = new File('apps/automation_map.groovy')
assert sourceFile.exists()
String source = sourceFile.getText('UTF-8')

assert !source.contains("content:'▸'")
assert !source.contains(" + ' · ' + ")
assert source.contains("#focusSection summary::before { content:'>';")
assert source.contains("SEARCH_ALL_GROUP_LABEL[n.group] + ' - ' + pickOptionText")

println 'PASS  map controls use ASCII-safe Focus and Quick Search separators'
