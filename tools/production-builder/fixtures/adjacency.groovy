// Line comment before a newline
def a = 1 // trailing line comment
/* single-line block comment */
def b = 2
/* multi
   line
   block
   comment */
def c = 3
def noSpace = a/*x*/b
def divide = 10 / 2
def divideNoSpace = 10/2
String single = 'a // not a comment /* also not */'
String doubleQ = "a // not a comment /* also not */"
String triple = '''a // not a comment
/* also not */ still string'''
String tripleDouble = """a // not a comment
/* also not */ still string ${1 + 1 /* comment inside gstring expr */}"""
String slashy = /a\/b # not a comment/
String dollarSlashy = $/a/b # not a comment/$
String escaped = 'it\'s a \\ test // not a comment'
def gstring = "value is ${a} // not a comment outside expr"
def gstringWithRealComment = "prefix" + /* real comment between concatenation */ "suffix"
