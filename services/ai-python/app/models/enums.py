from enum import Enum


class EmailLabel(str, Enum):
    IMPORTANT = "IMPORTANT"
    WORK = "work"
    PERSONAL = "personal"
    SPAM = "SPAM"
    SOCIAL = "CATEGORY_SOCIAL"
    PROMOTIONS = "CATEGORY_PROMOTIONS"
    UPDATES = "CATEGORY_UPDATES"
    FORUMS = "CATEGORY_FORUMS"
    FINANCE = "finance"
    TRAVEL = "travel"
    OTHER = "other"
