with open("app/src/main/java/com/vidlab/app/MainActivity.kt", "r") as f:
    text = f.read()

# Replace old MediaDetailScreen invocation with correct parameters
old_block = """MediaDetailScreen("""
# Let's write a robust replacement that sets up the correct parameters
