export default class Formatter {
    constructor() { }

    public date(date, text) {
        if (!text) text = "-";
        if (!date) return text;
        let res = this.formatDate(date);
        if (!res) return text;
        return res;
    }

    private formatDate(date) {
        if (typeof date === "string") {
            const value = date.trim();
            if (!value) return null;

            const match = /^(\d{4})-(\d{1,2})-(\d{1,2})$/.exec(value);
            if (match) {
                const year = Number(match[1]);
                const month = Number(match[2]);
                const day = Number(match[3]);
                const parsed = new Date(year, month - 1, day);
                if (parsed.getFullYear() !== year || parsed.getMonth() !== month - 1 || parsed.getDate() !== day) {
                    return null;
                }
                return this.formatDateParts(year, month, day);
            }
        }

        const parsed = date instanceof Date ? date : new Date(date);
        if (Number.isNaN(parsed.getTime())) return null;
        return this.formatDateParts(parsed.getFullYear(), parsed.getMonth() + 1, parsed.getDate());
    }

    private formatDateParts(year, month, day) {
        return [
            year.toString().padStart(4, "0"),
            month.toString().padStart(2, "0"),
            day.toString().padStart(2, "0"),
        ].join("-");
    }

    public currency(number, isString = true) {
        if (!number) return "-";
        if (isString) {
            let num = number.toString().replace(/\B(?=(\d{3})+(?!\d))/g, ",");
            return num + "원";
        }

        const units = ['', '만', '억', '조', '경'];
        const parts = [];
        let remaining = number;

        for (let i = units.length - 1; i >= 0; i--) {
            const unit = Math.pow(10000, i);
            const part = Math.floor(remaining / unit);
            remaining = remaining % unit;

            if (part > 0) {
                parts.push(part + units[i]);
            }
        }

        if (remaining > 0) {
            parts.push(remaining);
        }
        if (parts.length == 0) return '-';
        return parts.join(' ') + '원';
    }

}
