package project

import "testing"

func TestValidateAcceptsCanonicalRequest(t *testing.T) {
	spec, err := Validate("test-wiz", "kr.nanoha.testwiz", "angular-wiz")
	if err != nil {
		t.Fatalf("Validate returned an error: %v", err)
	}
	if spec.Template != "angular-wiz" {
		t.Fatalf("template = %q, want %q", spec.Template, "angular-wiz")
	}
	if spec.ProjectName != "test-wiz" || spec.PackageName != "kr.nanoha.testwiz" {
		t.Fatalf("unexpected spec: %#v", spec)
	}
}

func TestValidateAllowsJavaContextualIdentifiers(t *testing.T) {
	for _, identifier := range []string{"record", "module", "var", "sealed", "yield", "when", "exports"} {
		t.Run(identifier, func(t *testing.T) {
			if _, err := Validate("demo", "com.example."+identifier, "html"); err != nil {
				t.Fatalf("Validate rejected contextual identifier %q: %v", identifier, err)
			}
		})
	}
}

func TestValidateRejectsUnsafeValues(t *testing.T) {
	tests := []struct {
		name        string
		projectName string
		packageName string
		template    string
		field       string
	}{
		{"path traversal", "../demo", "com.example.demo", "html", "projectName"},
		{"slash", "group/demo", "com.example.demo", "html", "projectName"},
		{"uppercase normalization", "Demo", "com.example.demo", "html", "projectName"},
		{"leading dash", "-demo", "com.example.demo", "html", "projectName"},
		{"java namespace", "demo", "java.example", "html", "packageName"},
		{"package hyphen", "demo", "kr.nanoha.test-wiz", "html", "packageName"},
		{"keyword", "demo", "com.example.class", "html", "packageName"},
		{"empty segment", "demo", "com..example", "html", "packageName"},
		{"uppercase template", "demo", "com.example.demo", "Vue", "template"},
		{"blank template", "demo", "com.example.demo", "  ", "template"},
	}

	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			_, err := Validate(test.projectName, test.packageName, test.template)
			validation, ok := err.(*ValidationError)
			if !ok {
				t.Fatalf("error = %T %v, want *ValidationError", err, err)
			}
			if validation.Field != test.field {
				t.Fatalf("field = %q, want %q", validation.Field, test.field)
			}
		})
	}
}
